package com.claimpilot.claim.domain;

import java.time.Instant;

/** One line of the claim's audit trail. {@code from} is null for the first. */
public record Transition(ClaimStatus from, ClaimStatus to, String actor, String note, Instant at) {
}
