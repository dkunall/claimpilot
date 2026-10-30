package com.claimpilot.quote.application;

import com.claimpilot.product.application.ProductCatalog;
import com.claimpilot.product.domain.rating.RatingInput;
import com.claimpilot.quote.domain.Quote;
import com.claimpilot.shared.config.ClaimPilotProperties;
import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import com.claimpilot.shared.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
public class QuoteService {

    private final ProductCatalog catalog;
    private final QuoteRepository quotes;
    private final QuoteRateLimiter limiter;
    private final Clock clock;
    private final Duration validity;

    public QuoteService(ProductCatalog catalog, QuoteRepository quotes, QuoteRateLimiter limiter, Clock clock,
                        ClaimPilotProperties props) {
        this.catalog = catalog;
        this.quotes = quotes;
        this.limiter = limiter;
        this.clock = clock;
        this.validity = props.quotes().validity();
    }

    /** {@code holderSubject} is only for agents quoting on a customer's behalf. */
    public record QuoteRequest(String productId, String holderSubject, String holderName, String holderEmail,
                               RatingInput inputs) {
    }

    public Quote create(Caller caller, QuoteRequest r) {
        String holder;
        String agent;
        if (caller.is(Role.AGENT) || caller.is(Role.MANAGER)) {
            if (r.holderSubject() == null || r.holderSubject().isBlank()) {
                throw ApiException.invalid("holder-required", "agents quote for a customer: send holderSubject");
            }
            holder = r.holderSubject();
            agent = caller.subject();
        } else {
            caller.require(Role.CUSTOMER);
            holder = caller.subject();
            agent = null;
        }
        if (r.holderName() == null || r.holderName().isBlank() || r.holderEmail() == null
                || !r.holderEmail().contains("@") || r.inputs() == null || r.productId() == null) {
            throw ApiException.invalid("productId, holderName, holderEmail and inputs are required");
        }
        limiter.acquire(caller.subject());
        var product = catalog.latest(r.productId());
        var premium = catalog.price(product, r.inputs());
        var now = clock.instant();
        var q = new Quote(UUID.randomUUID(), product.productId(), product.version(), holder, agent, r.holderName(),
                r.holderEmail(), r.inputs(), premium, Quote.QuoteStatus.QUOTED, null, now, now.plus(validity));
        quotes.insert(q);
        return q;
    }

    public Quote get(Caller caller, UUID id) {
        return quotes.find(id).filter(q -> caller.canSee(q.holderSubject(), q.agentSubject()))
                .orElseThrow(() -> ApiException.notFound("quote"));
    }
}
