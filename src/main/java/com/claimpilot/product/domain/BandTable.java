package com.claimpilot.product.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ordered, non-overlapping bands such as "age up to 35 -> 1.0, up to 50 -> 1.4, above -> 2.1". Lookup is a
 * binary search for the first band whose upper bound is at or above the value: O(log b) per factor.
 */
public record BandTable(List<Band> bands) {

    public BandTable {
        if (bands == null || bands.isEmpty()) {
            throw new InvalidProductException("a band table needs at least one band");
        }
        for (int i = 0; i < bands.size(); i++) {
            Band b = bands.get(i);
            if (b.openEnded() && i != bands.size() - 1) {
                throw new InvalidProductException("only the last band may be open-ended");
            }
            if (i > 0 && !b.openEnded() && b.upTo().compareTo(bands.get(i - 1).upTo()) <= 0) {
                throw new InvalidProductException("band upper bounds must strictly increase");
            }
        }
        bands = List.copyOf(bands);
    }

    /** The band's value for {@code x}, or an exception if {@code x} is above the last closed band. */
    public BigDecimal lookup(BigDecimal x) {
        int lo = 0;
        int hi = bands.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Band b = bands.get(mid);
            if (b.openEnded() || x.compareTo(b.upTo()) <= 0) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (found < 0) {
            throw new RatingException(x.toPlainString() + " is above the highest band (" + bands.getLast().upTo().toPlainString() + ")");
        }
        return bands.get(found).value();
    }
}
