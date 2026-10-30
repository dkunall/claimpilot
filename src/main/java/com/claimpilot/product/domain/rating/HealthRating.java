package com.claimpilot.product.domain.rating;

import com.claimpilot.product.domain.LineOfBusiness;
import com.claimpilot.product.domain.RatingException;

import java.math.BigDecimal;
import java.util.List;

/** Health: base by sum insured, then band and category factors (age, members, region), riders, minimum. */
public final class HealthRating implements LineRating {

    @Override
    public LineOfBusiness line() {
        return LineOfBusiness.HEALTH;
    }

    @Override
    public List<RatingStep> chain() {
        return List.of(requireAge(), Steps.base(), Steps.bandFactors(), Steps.categoryFactors(), Steps.riders(),
                Steps.minimum());
    }

    /** Age drives health risk more than anything; refuse to price without it even if the table forgot it. */
    private static RatingStep requireAge() {
        return ctx -> {
            BigDecimal age = ctx.input().numeric().get("age");
            if (age == null || age.signum() < 0 || age.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new RatingException("health cover needs an age between 0 and 100");
            }
        };
    }
}
