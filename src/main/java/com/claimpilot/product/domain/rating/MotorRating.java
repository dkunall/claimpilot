package com.claimpilot.product.domain.rating;

import com.claimpilot.product.domain.LineOfBusiness;
import com.claimpilot.product.domain.RatingException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Motor: base by insured declared value, band and category factors (vehicle age, engine size, fuel), then
 * the no-claim bonus on the own-damage premium, riders, minimum. The NCB scale is the usual Indian one
 * (0/20/25/35/45/50% for 0..5+ claim-free years) and is part of the line, not the product.
 */
public final class MotorRating implements LineRating {

    private static final int[] NCB = {0, 20, 25, 35, 45, 50};

    @Override
    public LineOfBusiness line() {
        return LineOfBusiness.MOTOR;
    }

    @Override
    public List<RatingStep> chain() {
        return List.of(Steps.base(), Steps.bandFactors(), Steps.categoryFactors(), noClaimBonus(), Steps.riders(),
                Steps.minimum());
    }

    static int ncbPercent(int claimFreeYears) {
        return NCB[Math.min(Math.max(claimFreeYears, 0), NCB.length - 1)];
    }

    private static RatingStep noClaimBonus() {
        return ctx -> {
            BigDecimal years = ctx.input().numeric().get("claimFreeYears");
            if (years == null || years.signum() < 0 || years.stripTrailingZeros().scale() > 0) {
                throw new RatingException("motor cover needs claimFreeYears as a whole number >= 0");
            }
            int pct = ncbPercent(years.intValue());
            if (pct > 0) {
                BigDecimal discount = Steps.pct(ctx.premiumMinor(), BigDecimal.valueOf(pct));
                ctx.set("no-claim bonus", "-" + pct + "% for " + years.intValue() + " claim-free years",
                        ctx.premiumMinor().subtract(discount));
            }
        };
    }
}
