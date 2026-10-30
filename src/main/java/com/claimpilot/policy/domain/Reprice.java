package com.claimpilot.policy.domain;

import com.claimpilot.product.domain.rating.RatingInput;

/** Re-rates an input on the segment's own product version; supplied by the caller so the plan stays pure. */
@FunctionalInterface
public interface Reprice {

    Priced price(int productVersion, RatingInput input);

    record Priced(CoverageSnapshot snapshot, long annualPremiumMinor) {
    }
}
