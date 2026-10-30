package com.claimpilot.shared.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Postgres keeps microseconds; truncating first keeps an instant equal to itself after a round trip. */
public final class Times {

    private Times() {
    }

    public static Instant micros(Instant t) {
        return t.truncatedTo(ChronoUnit.MICROS);
    }
}
