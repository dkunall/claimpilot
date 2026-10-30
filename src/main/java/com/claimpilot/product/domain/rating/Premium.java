package com.claimpilot.product.domain.rating;

import java.util.List;

/** An annual premium: net of tax, the tax, and the total the customer pays. */
public record Premium(long netMinor, long taxMinor, long totalMinor, List<BreakdownLine> breakdown) {

    public Premium {
        breakdown = List.copyOf(breakdown);
    }
}
