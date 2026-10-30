package com.claimpilot.claim.domain;

import java.util.List;

/**
 * {@code reserveMinor} is what the policy could pay: the claim capped at the coverage limit, less the
 * deductible. {@code reasons} lists every straight-through rule that failed, or says that all passed.
 */
public record TriageResult(boolean stp, boolean investigate, long reserveMinor, List<String> reasons) {

    public TriageResult {
        reasons = List.copyOf(reasons);
        if (stp && investigate) {
            throw new IllegalArgumentException("a claim can't be both straight-through and investigated");
        }
    }
}
