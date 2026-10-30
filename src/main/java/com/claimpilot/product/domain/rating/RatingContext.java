package com.claimpilot.product.domain.rating;

import com.claimpilot.product.domain.ProductVersion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Mutable working state passed down the factor chain. Amounts stay unrounded until the end. */
public final class RatingContext {

    private final ProductVersion product;
    private final RatingInput input;
    private BigDecimal premiumMinor = BigDecimal.ZERO;
    private final List<BreakdownLine> lines = new ArrayList<>();

    RatingContext(ProductVersion product, RatingInput input) {
        this.product = product;
        this.input = input;
    }

    public ProductVersion product() {
        return product;
    }

    public RatingInput input() {
        return input;
    }

    public BigDecimal premiumMinor() {
        return premiumMinor;
    }

    public void set(String step, String detail, BigDecimal value) {
        premiumMinor = value;
        lines.add(new BreakdownLine(step, detail, value.setScale(0, RoundingMode.HALF_EVEN).longValueExact()));
    }

    public void multiply(String step, String detail, BigDecimal factor) {
        set(step, detail, premiumMinor.multiply(factor));
    }

    List<BreakdownLine> lines() {
        return lines;
    }
}
