package com.claimpilot.product.domain.rating;

import com.claimpilot.product.domain.BandTable;
import com.claimpilot.product.domain.RatingException;
import com.claimpilot.product.domain.Rider;

import java.math.BigDecimal;
import java.util.Map;

/** The steps every line shares. Line strategies add their own around these. */
final class Steps {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Steps() {
    }

    /** Base premium: sum insured times the rate per mille of its band (bands are in rupees). */
    static RatingStep base() {
        return ctx -> {
            BigDecimal sumMinor = BigDecimal.valueOf(ctx.input().sumInsuredMinor());
            BigDecimal rupees = sumMinor.divide(HUNDRED);
            BigDecimal rate = ctx.product().rating().baseRatePerMille().lookup(rupees);
            ctx.set("base", "sum insured " + rupees.toPlainString() + " at " + rate.toPlainString() + " per mille",
                    sumMinor.multiply(rate).divide(THOUSAND));
        };
    }

    /** One multiplier per configured band table, in name order so the breakdown is stable. */
    static RatingStep bandFactors() {
        return ctx -> {
            for (Map.Entry<String, BandTable> e : new java.util.TreeMap<>(ctx.product().rating().bandFactors()).entrySet()) {
                BigDecimal x = ctx.input().numeric().get(e.getKey());
                if (x == null) {
                    throw new RatingException("missing numeric input '" + e.getKey() + "'");
                }
                BigDecimal f = e.getValue().lookup(x);
                ctx.multiply(e.getKey(), e.getKey() + " " + x.toPlainString() + " -> x" + f.toPlainString(), f);
            }
        };
    }

    static RatingStep categoryFactors() {
        return ctx -> {
            for (var e : new java.util.TreeMap<>(ctx.product().rating().categoryFactors()).entrySet()) {
                String v = ctx.input().categorical().get(e.getKey());
                if (v == null) {
                    throw new RatingException("missing input '" + e.getKey() + "'");
                }
                BigDecimal f = e.getValue().get(v);
                if (f == null) {
                    throw new RatingException("'" + v + "' is not a known " + e.getKey() + " (known: "
                            + new java.util.TreeSet<>(e.getValue().keySet()) + ")");
                }
                ctx.multiply(e.getKey(), e.getKey() + " " + v + " -> x" + f.toPlainString(), f);
            }
        };
    }

    /** Rider loadings are all computed on the premium before riders, so their order doesn't matter. */
    static RatingStep riders() {
        return ctx -> {
            BigDecimal before = ctx.premiumMinor();
            for (String code : ctx.input().riders()) {
                Rider r = ctx.product().rider(code)
                        .orElseThrow(() -> new RatingException("rider " + code + " is not offered on this product"));
                BigDecimal loading = before.multiply(r.loadingPct()).divide(HUNDRED);
                ctx.set("rider " + code, "+" + r.loadingPct().toPlainString() + "%", ctx.premiumMinor().add(loading));
            }
        };
    }

    static RatingStep minimum() {
        return ctx -> {
            BigDecimal min = BigDecimal.valueOf(ctx.product().rating().minPremiumMinor());
            if (ctx.premiumMinor().compareTo(min) < 0) {
                ctx.set("minimum", "raised to the product minimum", min);
            }
        };
    }

    static BigDecimal pct(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(HUNDRED);
    }
}
