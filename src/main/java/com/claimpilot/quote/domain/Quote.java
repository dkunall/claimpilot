package com.claimpilot.quote.domain;

import com.claimpilot.product.domain.rating.Premium;
import com.claimpilot.product.domain.rating.RatingInput;

import java.time.Instant;
import java.util.UUID;

/** A priced offer, frozen to one product version until it expires or is bound. */
public record Quote(UUID id, String productId, int productVersion, String holderSubject, String agentSubject,
                    String holderName, String holderEmail, RatingInput inputs, Premium premium, QuoteStatus status,
                    UUID policyId, Instant createdAt, Instant expiresAt) {

    public enum QuoteStatus { QUOTED, BOUND }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
