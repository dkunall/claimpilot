package com.claimpilot.policy.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of bitemporal history. {@code [validFrom, validTo)} is when the cover applies in the world;
 * {@code [recordedFrom, recordedTo)} is when we believed it, with a null {@code recordedTo} meaning "still".
 */
public record PolicyVersion(
        Long id,
        Instant validFrom,
        Instant validTo,
        Instant recordedFrom,
        Instant recordedTo,
        CoverageSnapshot coverage,
        long annualPremiumMinor,
        String reason,
        UUID endorsementId) {

    public PolicyVersion {
        if (!validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException("empty valid range " + validFrom + ".." + validTo);
        }
    }

    public boolean current() {
        return recordedTo == null;
    }

    public boolean covers(Instant t) {
        return !t.isBefore(validFrom) && t.isBefore(validTo);
    }

    public PolicyVersion segment(Instant from, Instant to, Instant recorded, CoverageSnapshot snap, long premium,
                                 String why, UUID endorsement) {
        return new PolicyVersion(null, from, to, recorded, null, snap, premium, why, endorsement);
    }
}
