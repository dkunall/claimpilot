package com.claimpilot.policy.domain;

import java.security.SecureRandom;

/** Human-friendly policy numbers: CP-H-XXXXXXXXXX, Crockford base32 (no I, L, O, U), 50 random bits. */
public final class PolicyNumbers {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PolicyNumbers() {
    }

    public static String next(String linePrefix) {
        long bits = RANDOM.nextLong() >>> 14;
        char[] out = new char[10];
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (bits & 31)];
            bits >>>= 5;
        }
        return "CP-" + linePrefix + "-" + new String(out);
    }
}
