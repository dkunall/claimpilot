package com.claimpilot.shared.error;

public class RateLimitedException extends ApiException {

    private final int retryAfterSeconds;

    public RateLimitedException(String message, int retryAfterSeconds) {
        super(429, "rate-limited", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
