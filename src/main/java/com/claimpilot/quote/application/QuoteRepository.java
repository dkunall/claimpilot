package com.claimpilot.quote.application;

import com.claimpilot.quote.domain.Quote;

import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository {

    void insert(Quote q);

    Optional<Quote> find(UUID id);

    /** Row-locks the quote for the rest of the transaction. */
    Optional<Quote> lock(UUID id);

    void markBound(UUID id, UUID policyId);
}
