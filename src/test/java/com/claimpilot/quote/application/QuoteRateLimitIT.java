package com.claimpilot.quote.application;

import com.claimpilot.shared.config.ClaimPilotProperties;
import com.claimpilot.support.Api;
import com.claimpilot.support.ItBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Its own context with a tiny quote budget (burst 3, 2 per minute), so the limit is visible over HTTP. */
@Import(QuoteRateLimitIT.Tight.class)
class QuoteRateLimitIT extends ItBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class Tight {
        @Bean
        @Primary
        QuoteRateLimiter tightQuoteLimiter() {
            return new QuoteRateLimiter(new ClaimPilotProperties.Quotes(2, 3, Duration.ofMinutes(30)), System::nanoTime);
        }
    }

    @Test
    void fourthQuoteInABurstIs429WithRetryAfterAndOthersAreUnaffected() {
        Api c = as(unique("spammer"));
        var body = quoteBody("health-shield", healthInputs(36_500_000, 30));
        for (int i = 0; i < 3; i++) {
            c.post("/v1/quotes", body).expect(201);
        }
        Api.Response limited = c.post("/v1/quotes", body).expect(429);
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 30);
        as(unique("other")).post("/v1/quotes", body).expect(201);
    }
}
