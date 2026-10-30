package com.claimpilot.product.domain;

import java.util.regex.Pattern;

final class Checks {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

    private Checks() {
    }

    static void code(String value, String what) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new InvalidProductException(what + " must look like OPD or ROAD_SIDE (upper case, 2-32 chars)");
        }
    }

    static void text(String value, String what) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new InvalidProductException(what + " must be 1-120 characters");
        }
    }
}
