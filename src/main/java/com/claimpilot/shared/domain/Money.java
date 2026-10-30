package com.claimpilot.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee amounts are carried as paise in a long. This only formats them for people. */
public final class Money {

    private Money() {
    }

    public static String inr(long minor) {
        return "INR " + BigDecimal.valueOf(minor).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
