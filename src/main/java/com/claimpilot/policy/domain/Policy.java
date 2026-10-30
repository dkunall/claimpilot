package com.claimpilot.policy.domain;

import java.time.Instant;
import java.util.UUID;

/** The non-temporal part of a policy: who holds it, which term it is in, and its lifecycle state. */
public record Policy(
        UUID id,
        String number,
        String holderSubject,
        String agentSubject,
        String holderName,
        String holderEmail,
        String productId,
        int productVersion,
        PolicyStatus status,
        Instant firstStart,
        Instant termStart,
        Instant termEnd,
        Instant graceUntil,
        String autopayToken,
        long lockVersion,
        Instant createdAt) {

    public Policy withStatus(PolicyStatus next) {
        return new Policy(id, number, holderSubject, agentSubject, holderName, holderEmail, productId, productVersion,
                status.moveTo(next), firstStart, termStart, termEnd, graceUntil, autopayToken, lockVersion, createdAt);
    }
}
