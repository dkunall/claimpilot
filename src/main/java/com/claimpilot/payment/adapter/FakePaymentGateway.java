package com.claimpilot.payment.adapter;

import com.claimpilot.payment.application.PaymentGateway;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for a real gateway. Tokens starting with {@code tok_decline} are declined; everything else succeeds.
 * An approved key always returns its first answer, so a retry can't move money twice; a declined attempt does
 * not use the key up, so the customer can retry with a working card. References derive from the key.
 * {@link #calls()} counts distinct keys that actually "moved money", which tests use to catch double charges.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private final Map<String, Result> seen = new ConcurrentHashMap<>();
    private final AtomicLong moved = new AtomicLong();

    @Override
    public Result charge(String key, String token, long amountMinor) {
        return once("charge:" + key, "ch_", token, amountMinor);
    }

    @Override
    public Result payout(String key, String token, long amountMinor) {
        return once("payout:" + key, "po_", token, amountMinor);
    }

    private Result once(String key, String prefix, String token, long amountMinor) {
        Result r = seen.computeIfAbsent(key, k -> decide(prefix, k, token, amountMinor));
        if (!r.approved()) {
            seen.remove(key, r);
        }
        return r;
    }

    public long calls() {
        return moved.get();
    }

    /** How many distinct approved charge or payout keys start with {@code prefix}, e.g. {@code "charge:bind:"}. */
    public long approved(String prefix) {
        return seen.entrySet().stream().filter(e -> e.getKey().startsWith(prefix) && e.getValue().approved()).count();
    }

    private Result decide(String prefix, String key, String token, long amountMinor) {
        if (amountMinor <= 0) {
            return new Result(false, prefix + ref(key), "amount must be positive");
        }
        if (token == null || token.startsWith("tok_decline")) {
            return new Result(false, prefix + ref(key), "card declined (test token)");
        }
        moved.incrementAndGet();
        return new Result(true, prefix + ref(key), null);
    }

    private static String ref(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
