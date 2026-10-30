package com.claimpilot.quote.domain;

/**
 * Classic token bucket on a caller-supplied monotonic clock (nanoseconds). Not thread-safe on its own; callers
 * synchronise per bucket.
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long last;

    public TokenBucket(int capacity, int perMinute, long nowNanos) {
        if (capacity <= 0 || perMinute <= 0) {
            throw new IllegalArgumentException("capacity and rate must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = perMinute / 60e9;
        this.tokens = capacity;
        this.last = nowNanos;
    }

    /** Takes a token, or returns how many whole seconds until one is available. */
    public long tryTake(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / refillPerNano / 1e9);
    }

    public boolean full(long nowNanos) {
        refill(nowNanos);
        return tokens >= capacity;
    }

    private void refill(long nowNanos) {
        long elapsed = Math.max(0, nowNanos - last);
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        last = nowNanos;
    }
}
