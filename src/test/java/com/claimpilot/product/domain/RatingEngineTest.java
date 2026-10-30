package com.claimpilot.product.domain;

import com.claimpilot.product.domain.rating.BreakdownLine;
import com.claimpilot.product.domain.rating.Premium;
import com.claimpilot.product.domain.rating.RatingEngine;
import com.claimpilot.product.domain.rating.RatingInput;
import com.claimpilot.support.Seeds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RatingEngineTest {

    private final RatingEngine engine = RatingEngine.standard();

    private static RatingInput health(long sumInsuredRupees, int age, int members, String region, String... riders) {
        return new RatingInput(sumInsuredRupees * 100, Map.of("age", BigDecimal.valueOf(age),
                "members", BigDecimal.valueOf(members)), Map.of("region", region), Set.of(riders));
    }

    @Test
    void healthPremiumWorkedByHand() {
        // 5L at 15 per mille = 7,500.00; x1.35 (age 40) x1.7 (2 members) x1.15 (metro) = 19,794.375;
        // +12% critical illness = 22,169.70 net; 18% tax = 3,990.546 -> 3,990.55
        Premium p = engine.price(Seeds.health(), health(500_000, 40, 2, "METRO", "CRITICAL_ILLNESS"));
        assertThat(p.netMinor()).isEqualTo(2_216_970);
        assertThat(p.taxMinor()).isEqualTo(399_055);
        assertThat(p.totalMinor()).isEqualTo(2_616_025);
        assertThat(p.breakdown()).extracting(BreakdownLine::step)
                .containsExactly("base", "age", "members", "region", "rider CRITICAL_ILLNESS", "tax");
    }

    @Test
    void motorAppliesNoClaimBonusBeforeRiders() {
        // 6L IDV at 25 = 15,000; x1.0 (1200cc) x1.15 (4 yrs) x1.05 (diesel) = 18,112.50; -35% NCB = 11,773.125
        Premium p = engine.price(Seeds.motor(), new RatingInput(60_000_000,
                Map.of("vehicleAgeYears", BigDecimal.valueOf(4), "engineCc", BigDecimal.valueOf(1200),
                        "claimFreeYears", BigDecimal.valueOf(3)), Map.of("fuel", "DIESEL"), Set.of()));
        assertThat(p.netMinor()).isEqualTo(1_177_312); // 1,177,312.5 rounds half-even down
        assertThat(p.totalMinor()).isEqualTo(1_177_312 + 211_916);
        assertThat(p.breakdown()).extracting(BreakdownLine::step).contains("no-claim bonus");
    }

    @Test
    void ncbCapsAtFiftyPercent() {
        RatingInput base = new RatingInput(60_000_000, Map.of("vehicleAgeYears", BigDecimal.ONE,
                "engineCc", BigDecimal.valueOf(1200), "claimFreeYears", BigDecimal.ZERO), Map.of("fuel", "PETROL"), Set.of());
        long none = engine.price(Seeds.motor(), base).netMinor();
        long five = engine.price(Seeds.motor(), base.withNumeric(Map.of("vehicleAgeYears", BigDecimal.ONE,
                "engineCc", BigDecimal.valueOf(1200), "claimFreeYears", BigDecimal.valueOf(5)))).netMinor();
        long nine = engine.price(Seeds.motor(), base.withNumeric(Map.of("vehicleAgeYears", BigDecimal.ONE,
                "engineCc", BigDecimal.valueOf(1200), "claimFreeYears", BigDecimal.valueOf(9)))).netMinor();
        assertThat(five).isEqualTo(none / 2);
        assertThat(nine).isEqualTo(five);
    }

    @Test
    void minimumPremiumApplies() {
        // 1L at 18 per mille, age 20 (x0.8), tier 3 (x0.9) = 1,296: below the 3,000 floor
        Premium p = engine.price(Seeds.health(), health(100_000, 20, 1, "TIER3"));
        assertThat(p.netMinor()).isEqualTo(300_000);
        assertThat(p.breakdown()).extracting(BreakdownLine::step).contains("minimum");
    }

    @Test
    void sameInputSamePremiumAndRiderOrderDoesNotMatter() {
        Premium a = engine.price(Seeds.health(), health(700_000, 33, 4, "TIER2", "MATERNITY", "CRITICAL_ILLNESS"));
        Premium b = engine.price(Seeds.health(), health(700_000, 33, 4, "TIER2", "CRITICAL_ILLNESS", "MATERNITY"));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void refusesWhatItCannotPrice() {
        assertThatThrownBy(() -> engine.price(Seeds.health(), health(500_000, 40, 2, "MARS")))
                .isInstanceOf(RatingException.class).hasMessageContaining("not a known region");
        assertThatThrownBy(() -> engine.price(Seeds.health(), health(500_000, 101, 2, "METRO")))
                .isInstanceOf(RatingException.class);
        assertThatThrownBy(() -> engine.price(Seeds.health(), health(500_000, 40, 2, "METRO", "ZERO_DEP")))
                .isInstanceOf(RatingException.class).hasMessageContaining("not offered");
        assertThatThrownBy(() -> engine.price(Seeds.health(), new RatingInput(10_000_000, Map.of("age",
                BigDecimal.TEN), Map.of("region", "METRO"), Set.of())))
                .isInstanceOf(RatingException.class).hasMessageContaining("members");
    }
}
