package com.claimpilot.fraud.application;

import com.claimpilot.fraud.domain.PartyRef;
import com.claimpilot.fraud.domain.PartyType;
import com.claimpilot.shared.config.ClaimPilotProperties;
import com.claimpilot.shared.error.ApiException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Turns a raw phone number, bank account or device id into a keyed HMAC-SHA256. Matching still works (same
 * input, same hash) but a database dump alone can't be reversed by hashing every phone number in India, which
 * a plain SHA-256 would allow. Raw values are never stored or logged.
 */
@Component
public class PartyHasher {

    private final SecretKeySpec key;

    public PartyHasher(ClaimPilotProperties props) {
        String k = props.security().partyHmacKey();
        if (k == null || k.length() < 16) {
            throw new IllegalStateException("claimpilot.security.party-hmac-key must be set (16+ chars)");
        }
        this.key = new SecretKeySpec(k.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public PartyRef hash(PartyType type, String raw) {
        String n = normalise(type, raw);
        if (n.isEmpty()) {
            throw ApiException.invalid("invalid-party", type + " value is empty after normalising");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(type.name().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return new PartyRef(type, HexFormat.of().formatHex(mac.doFinal(n.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Spelling differences must not hide a match: "+91 98-7654 3210" and "9876543210" are one phone. */
    static String normalise(PartyType type, String raw) {
        if (raw == null) {
            return "";
        }
        return switch (type) {
            case PHONE -> {
                String d = raw.replaceAll("\\D", "");
                yield d.length() > 10 ? d.substring(d.length() - 10) : d;
            }
            case BANK_ACCOUNT -> raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
            case DEVICE -> raw.strip().toLowerCase(Locale.ROOT);
            case GARAGE, HOSPITAL -> raw.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        };
    }
}
