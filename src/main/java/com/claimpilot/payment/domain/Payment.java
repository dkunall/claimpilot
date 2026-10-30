package com.claimpilot.payment.domain;

import java.time.Instant;
import java.util.UUID;

/** A money movement on a policy. Refunds are negative. One row per gateway key, so retries can't double it. */
public record Payment(UUID id, UUID policyId, PaymentKind kind, Instant periodStart, long amountMinor,
                      boolean succeeded, String gatewayRef, String gatewayKey, String receiptNo, Instant createdAt) {
}
