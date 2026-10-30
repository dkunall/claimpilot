package com.claimpilot.policy.domain;

import com.claimpilot.product.domain.rating.RatingInput;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A mid-term change. Every field is optional; together they are applied to the rating inputs of each affected
 * segment. {@code cancel} ends cover at the effective date instead.
 */
public record EndorsementChange(
        Long sumInsuredMinor,
        Set<String> addRiders,
        Set<String> removeRiders,
        Map<String, BigDecimal> numeric,
        Map<String, String> categorical,
        boolean cancel) {

    public EndorsementChange {
        // sorted, so the same change always serialises the same way (idempotency hashes depend on it)
        addRiders = sorted(addRiders);
        removeRiders = sorted(removeRiders);
        numeric = numeric == null ? Collections.emptySortedMap() : Collections.unmodifiableSortedMap(new TreeMap<>(numeric));
        categorical = categorical == null ? Collections.emptySortedMap()
                : Collections.unmodifiableSortedMap(new TreeMap<>(categorical));
        boolean changesCover = sumInsuredMinor != null || !addRiders.isEmpty() || !removeRiders.isEmpty()
                || !numeric.isEmpty() || !categorical.isEmpty();
        if (cancel == changesCover) {
            throw new PolicyRuleException("invalid-endorsement",
                    "an endorsement either cancels or changes cover, and must do one of them");
        }
        if (sumInsuredMinor != null && sumInsuredMinor <= 0) {
            throw new PolicyRuleException("invalid-endorsement", "sum insured must be positive");
        }
        if (addRiders.stream().anyMatch(removeRiders::contains)) {
            throw new PolicyRuleException("invalid-endorsement", "a rider can't be both added and removed");
        }
    }

    private static Set<String> sorted(Set<String> in) {
        return in == null ? Collections.emptySortedSet() : Collections.unmodifiableSortedSet(new TreeSet<>(in));
    }

    public static EndorsementChange cancellation() {
        return new EndorsementChange(null, null, null, null, null, true);
    }

    public RatingInput applyTo(RatingInput in) {
        RatingInput out = in;
        if (sumInsuredMinor != null) {
            out = out.withSumInsured(sumInsuredMinor);
        }
        if (!addRiders.isEmpty() || !removeRiders.isEmpty()) {
            Set<String> r = new TreeSet<>(out.riders());
            r.addAll(addRiders);
            r.removeAll(removeRiders);
            out = out.withRiders(r);
        }
        if (!numeric.isEmpty()) {
            Map<String, BigDecimal> n = new TreeMap<>(out.numeric());
            n.putAll(numeric);
            out = out.withNumeric(n);
        }
        if (!categorical.isEmpty()) {
            Map<String, String> c = new TreeMap<>(out.categorical());
            c.putAll(categorical);
            out = out.withCategorical(c);
        }
        return out;
    }
}
