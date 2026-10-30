package com.claimpilot.product.domain;

import java.math.BigDecimal;

/** An optional add-on priced as a percentage loading on the premium so far. */
public record Rider(String code, String name, BigDecimal loadingPct) {

    public Rider {
        Checks.code(code, "rider code");
        Checks.text(name, "rider name");
        if (loadingPct == null || loadingPct.signum() < 0 || loadingPct.compareTo(BigDecimal.valueOf(200)) > 0) {
            throw new InvalidProductException("rider " + code + ": loading must be between 0 and 200 percent");
        }
    }
}
