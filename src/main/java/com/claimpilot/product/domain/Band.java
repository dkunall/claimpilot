package com.claimpilot.product.domain;

import java.math.BigDecimal;

/** A band covers values up to and including {@code upTo}; a null {@code upTo} is open-ended. */
public record Band(BigDecimal upTo, BigDecimal value) {

    public Band {
        if (value == null || value.signum() < 0) {
            throw new InvalidProductException("band value must be zero or more");
        }
    }

    boolean openEnded() {
        return upTo == null;
    }
}
