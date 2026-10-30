package com.claimpilot.fraud.domain;

import java.util.List;

/** A score in [0, 1] with the reasons behind it, so an assessor can see why a claim was held. */
public record FraudScore(double score, List<String> reasons) {

    public static final FraudScore NONE = new FraudScore(0, List.of());

    public FraudScore {
        if (!(score >= 0 && score <= 1)) {
            throw new IllegalArgumentException("fraud score must be in [0, 1]: " + score);
        }
        reasons = List.copyOf(reasons);
    }
}
