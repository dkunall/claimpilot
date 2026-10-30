package com.claimpilot.product.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The data behind a price. {@code baseRatePerMille} is looked up by sum insured (in rupees) and gives the
 * annual rate per 1,000 of sum insured. {@code bandFactors} multiply by numeric inputs (age, vehicle age),
 * {@code categoryFactors} by categorical ones (region, fuel). Tax is added last.
 */
public record RatingTable(
        BandTable baseRatePerMille,
        Map<String, BandTable> bandFactors,
        Map<String, Map<String, BigDecimal>> categoryFactors,
        long minPremiumMinor,
        BigDecimal taxPct) {

    public RatingTable {
        if (baseRatePerMille == null) {
            throw new InvalidProductException("rating needs a base rate table");
        }
        bandFactors = bandFactors == null ? Map.of() : Map.copyOf(bandFactors);
        categoryFactors = categoryFactors == null ? Map.of() : Map.copyOf(categoryFactors);
        if (minPremiumMinor < 0 || taxPct == null || taxPct.signum() < 0 || taxPct.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw new InvalidProductException("min premium must be >= 0 and tax between 0 and 50 percent");
        }
    }
}
