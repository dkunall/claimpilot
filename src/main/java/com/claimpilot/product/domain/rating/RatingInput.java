package com.claimpilot.product.domain.rating;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** What the customer told us: sum insured, numeric factors (age), categorical ones (region) and riders. */
public record RatingInput(long sumInsuredMinor, Map<String, BigDecimal> numeric, Map<String, String> categorical,
                          Set<String> riders) {

    public RatingInput {
        if (sumInsuredMinor <= 0) {
            throw new com.claimpilot.product.domain.RatingException("sum insured must be positive");
        }
        // sorted copies, so equal inputs serialise identically (quotes are compared and hashed)
        numeric = numeric == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(numeric));
        categorical = categorical == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(categorical));
        riders = riders == null ? Set.of() : java.util.Collections.unmodifiableSet(new TreeSet<>(riders));
    }

    public RatingInput withSumInsured(long minor) {
        return new RatingInput(minor, numeric, categorical, riders);
    }

    public RatingInput withRiders(Set<String> next) {
        return new RatingInput(sumInsuredMinor, numeric, categorical, next);
    }

    public RatingInput withNumeric(Map<String, BigDecimal> next) {
        return new RatingInput(sumInsuredMinor, next, categorical, riders);
    }

    public RatingInput withCategorical(Map<String, String> next) {
        return new RatingInput(sumInsuredMinor, numeric, next, riders);
    }
}
