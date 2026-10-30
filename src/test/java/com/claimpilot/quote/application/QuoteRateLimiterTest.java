package com.claimpilot.quote.application;

import com.claimpilot.shared.config.ClaimPilotProperties;
import com.claimpilot.shared.error.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuoteRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final QuoteRateLimiter limiter = new QuoteRateLimiter(
            new ClaimPilotProperties.Quotes(6, 3, Duration.ofMinutes(30)), clock::get);

    @Test
    void burstThenRetryAfterThenRefill() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("a");
        }
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOfSatisfying(RateLimitedException.class,
                e -> assertThat(e.retryAfterSeconds()).isEqualTo(10)); // 6/min = one every 10 s
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        limiter.acquire("a");
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void budgetsArePerSubject() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("a");
        }
        limiter.acquire("b");
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(RateLimitedException.class);
    }
}
