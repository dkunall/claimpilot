package com.claimpilot.claim.domain;

import java.time.Instant;
import java.util.UUID;

/** The payout. One per claim; the database key makes a second one impossible. */
public record Settlement(UUID claimId, long amountMinor, String gatewayRef, String settledBy, Instant at) {
}
