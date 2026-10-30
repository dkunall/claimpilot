package com.claimpilot.policy.domain;

import com.claimpilot.product.domain.rating.RatingInput;
import com.claimpilot.support.Seeds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndorsementPlanTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = START.plus(Duration.ofDays(365));
    private static final Instant R0 = Instant.parse("2026-01-01T09:00:00Z");

    /** Premium is simply sum insured / 100 so the arithmetic is visible. */
    private static final Reprice REPRICE = (v, in) ->
            new Reprice.Priced(CoverageSnapshot.of(Seeds.health(), in), in.sumInsuredMinor() / 100);

    private static RatingInput input(long sumInsured) {
        return new RatingInput(sumInsured, Map.of("age", BigDecimal.valueOf(30), "members", BigDecimal.ONE),
                Map.of("region", "METRO"), Set.of());
    }

    private static PolicyVersion row(long id, Instant from, Instant to, long sumInsured) {
        return new PolicyVersion(id, from, to, R0, null, CoverageSnapshot.of(Seeds.health(), input(sumInsured)),
                sumInsured / 100, "issue", null);
    }

    private static EndorsementChange sumInsured(long v) {
        return new EndorsementChange(v, null, null, null, null, false);
    }

    private static Instant day(int d) {
        return START.plus(Duration.ofDays(d));
    }

    @Test
    void midTermChangeSplitsTheSegmentAndProRates() {
        var plan = EndorsementPlan.plan(List.of(row(1, START, END, 36_500_000)), day(100), day(100),
                sumInsured(73_000_000), REPRICE, UUID.randomUUID());
        assertThat(plan.close()).containsExactly(1L);
        assertThat(plan.insert()).hasSize(2);
        assertThat(plan.insert().get(0).validFrom()).isEqualTo(START);
        assertThat(plan.insert().get(0).validTo()).isEqualTo(day(100));
        assertThat(plan.insert().get(0).annualPremiumMinor()).isEqualTo(365_000);
        assertThat(plan.insert().get(1).validFrom()).isEqualTo(day(100));
        assertThat(plan.insert().get(1).annualPremiumMinor()).isEqualTo(730_000);
        // 365,000 more a year for 265 of 365 days
        assertThat(plan.premiumDeltaMinor()).isEqualTo(265_000);
    }

    @Test
    void backdatedChangeRewritesEveryLaterSegmentButKeepsTheirOwnChanges() {
        // after an earlier endorsement on day 100 that added a rider
        PolicyVersion a = row(1, START, day(100), 36_500_000);
        RatingInput withRider = input(36_500_000).withRiders(Set.of("MATERNITY"));
        PolicyVersion b = new PolicyVersion(2L, day(100), END, R0, null,
                CoverageSnapshot.of(Seeds.health(), withRider), 400_000, "endorsement", null);
        var plan = EndorsementPlan.plan(new ArrayList<>(List.of(b, a)), day(50), day(200),
                sumInsured(73_000_000), REPRICE, UUID.randomUUID());
        assertThat(plan.close()).containsExactly(1L, 2L);
        assertThat(plan.insert()).extracting(PolicyVersion::validFrom).containsExactly(START, day(50), day(100));
        assertThat(plan.insert()).extracting(PolicyVersion::validTo).containsExactly(day(50), day(100), END);
        // the later segment keeps its rider and gets the new sum insured
        assertThat(plan.insert().get(2).coverage().inputs().riders()).containsExactly("MATERNITY");
        assertThat(plan.insert().get(2).coverage().inputs().sumInsuredMinor()).isEqualTo(73_000_000);
        // segments stay contiguous with no overlap
        for (int i = 1; i < plan.insert().size(); i++) {
            assertThat(plan.insert().get(i).validFrom()).isEqualTo(plan.insert().get(i - 1).validTo());
        }
        // (730,000 - 365,000) x 50 days + (730,000 - 400,000) x 265 days, over 365
        assertThat(plan.premiumDeltaMinor()).isEqualTo(Math.round((365_000.0 * 50 + 330_000.0 * 265) / 365));
    }

    @Test
    void cancellationTruncatesAndRefundsTheRemainder() {
        var plan = EndorsementPlan.plan(List.of(row(1, START, END, 36_500_000)), day(200), day(200),
                EndorsementChange.cancellation(), REPRICE, UUID.randomUUID());
        assertThat(plan.insert()).singleElement().satisfies(r -> {
            assertThat(r.validFrom()).isEqualTo(START);
            assertThat(r.validTo()).isEqualTo(day(200));
        });
        assertThat(plan.premiumDeltaMinor()).isEqualTo(-165_000);
    }

    @Test
    void cancellingFromInceptionLeavesNoCurrentCover() {
        var plan = EndorsementPlan.plan(List.of(row(1, START, END, 36_500_000)), START, day(3),
                EndorsementChange.cancellation(), REPRICE, UUID.randomUUID());
        assertThat(plan.insert()).isEmpty();
        assertThat(plan.premiumDeltaMinor()).isEqualTo(-365_000);
    }

    @Test
    void effectiveDateMustBeInsideCover() {
        var rows = List.of(row(1, START, END, 36_500_000));
        assertThatThrownBy(() -> EndorsementPlan.plan(rows, END, day(1), sumInsured(1), REPRICE, UUID.randomUUID()))
                .isInstanceOf(PolicyRuleException.class).hasMessageContaining("inside the cover period");
        assertThatThrownBy(() -> EndorsementPlan.plan(rows, START.minusSeconds(1), day(1), sumInsured(1), REPRICE,
                UUID.randomUUID())).isInstanceOf(PolicyRuleException.class);
    }

    @Test
    void recordTimeNeverGoesBackwards() {
        // the clock reads earlier than the row we're superseding (clock skew between instances)
        var plan = EndorsementPlan.plan(List.of(row(1, START, END, 36_500_000)), day(10), R0.minusSeconds(5),
                sumInsured(40_000_000), REPRICE, UUID.randomUUID());
        assertThat(plan.recordedAt()).isAfter(R0);
    }

    @Test
    void anEndorsementMustSayWhatItChanges() {
        assertThatThrownBy(() -> new EndorsementChange(null, null, null, null, null, false))
                .isInstanceOf(PolicyRuleException.class);
        assertThatThrownBy(() -> new EndorsementChange(5L, null, null, null, null, true))
                .isInstanceOf(PolicyRuleException.class);
    }

    @Test
    void lifecycleRejectsIllegalMoves() {
        assertThat(PolicyStatus.ACTIVE.moveTo(PolicyStatus.GRACE)).isEqualTo(PolicyStatus.GRACE);
        assertThat(PolicyStatus.GRACE.moveTo(PolicyStatus.ACTIVE)).isEqualTo(PolicyStatus.ACTIVE);
        assertThatThrownBy(() -> PolicyStatus.LAPSED.moveTo(PolicyStatus.ACTIVE)).isInstanceOf(PolicyRuleException.class);
        assertThatThrownBy(() -> PolicyStatus.ACTIVE.moveTo(PolicyStatus.LAPSED)).isInstanceOf(PolicyRuleException.class);
        assertThatThrownBy(() -> PolicyStatus.CANCELLED.moveTo(PolicyStatus.ACTIVE)).isInstanceOf(PolicyRuleException.class);
    }
}
