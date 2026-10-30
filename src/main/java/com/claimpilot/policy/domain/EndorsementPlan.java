package com.claimpilot.policy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Works out the bitemporal rewrite for an endorsement effective at {@code E}, recorded at {@code now}.
 *
 * <p>Current rows that end at or before E are untouched. Every current row that ends after E is closed in
 * record time, and replaced by: the part before E unchanged (if it straddles E), and the part from E on with
 * the change applied and re-rated. Nothing is updated in place, so "what did we believe on date X" keeps its
 * answer. The premium difference is pro-rated by the seconds of cover affected over a 365-day year.
 */
public record EndorsementPlan(List<Long> close, List<PolicyVersion> insert, long premiumDeltaMinor, Instant recordedAt) {

    private static final BigDecimal YEAR_SECONDS = BigDecimal.valueOf(Duration.ofDays(365).toSeconds());

    public static EndorsementPlan plan(List<PolicyVersion> current, Instant effective, Instant now,
                                       EndorsementChange change, Reprice reprice, UUID endorsementId) {
        List<PolicyVersion> rows = current.stream().filter(PolicyVersion::current)
                .sorted(Comparator.comparing(PolicyVersion::validFrom)).toList();
        if (rows.isEmpty()) {
            throw new PolicyRuleException("no-cover", "the policy has no current cover to endorse");
        }
        Instant start = rows.getFirst().validFrom();
        Instant end = rows.getLast().validTo();
        if (effective.isBefore(start) || !effective.isBefore(end)) {
            throw new PolicyRuleException("outside-cover", "effective date must be inside the cover period "
                    + start + " .. " + end);
        }
        // A recorded range can't be empty, so the new belief starts strictly after the newest one we close.
        Instant recordedAt = now;
        for (PolicyVersion r : rows) {
            if (!recordedAt.isAfter(r.recordedFrom())) {
                recordedAt = r.recordedFrom().plusNanos(1000);
            }
        }

        List<Long> close = new ArrayList<>();
        List<PolicyVersion> insert = new ArrayList<>();
        BigDecimal delta = BigDecimal.ZERO;
        String why = change.cancel() ? "cancellation" : "endorsement";
        for (PolicyVersion r : rows) {
            if (!r.validTo().isAfter(effective)) {
                continue;
            }
            close.add(r.id());
            if (r.validFrom().isBefore(effective)) {
                insert.add(r.segment(r.validFrom(), effective, recordedAt, r.coverage(), r.annualPremiumMinor(),
                        r.reason(), r.endorsementId()));
            }
            Instant from = r.validFrom().isBefore(effective) ? effective : r.validFrom();
            BigDecimal seconds = BigDecimal.valueOf(Duration.between(from, r.validTo()).toSeconds());
            if (change.cancel()) {
                delta = delta.subtract(BigDecimal.valueOf(r.annualPremiumMinor()).multiply(seconds));
                continue;
            }
            Reprice.Priced p = reprice.price(r.coverage().productVersion(), change.applyTo(r.coverage().inputs()));
            insert.add(r.segment(from, r.validTo(), recordedAt, p.snapshot(), p.annualPremiumMinor(), why, endorsementId));
            delta = delta.add(BigDecimal.valueOf(p.annualPremiumMinor() - r.annualPremiumMinor()).multiply(seconds));
        }
        long deltaMinor = delta.divide(YEAR_SECONDS, 0, RoundingMode.HALF_EVEN).longValueExact();
        return new EndorsementPlan(List.copyOf(close), List.copyOf(insert), deltaMinor, recordedAt);
    }
}
