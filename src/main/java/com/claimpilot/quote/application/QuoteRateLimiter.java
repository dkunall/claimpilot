package com.claimpilot.quote.application;

import com.claimpilot.quote.domain.TokenBucket;
import com.claimpilot.shared.config.ClaimPilotProperties;
import com.claimpilot.shared.error.RateLimitedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-subject quote limit. Pricing is cheap, but quote spam is how rating tables get scraped. In-memory, so each
 * instance enforces its own budget (see README).
 */
@Component
public class QuoteRateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ClaimPilotProperties.Quotes cfg;
    private final LongSupplier nanos;

    @Autowired
    public QuoteRateLimiter(ClaimPilotProperties props) {
        this(props.quotes(), System::nanoTime);
    }

    QuoteRateLimiter(ClaimPilotProperties.Quotes cfg, LongSupplier nanos) {
        this.cfg = cfg;
        this.nanos = nanos;
    }

    public void acquire(String subject) {
        long now = nanos.getAsLong();
        if (buckets.size() > 10_000) {
            buckets.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().full(now);
                }
            });
        }
        TokenBucket b = buckets.computeIfAbsent(subject, s -> new TokenBucket(cfg.burst(), cfg.perMinute(), now));
        long wait;
        synchronized (b) {
            wait = b.tryTake(now);
        }
        if (wait > 0) {
            throw new RateLimitedException("too many quotes; slow down", (int) Math.min(wait, 3600));
        }
    }
}
