package com.claimpilot.policy.domain;

import com.claimpilot.product.domain.Coverage;
import com.claimpilot.product.domain.ProductVersion;
import com.claimpilot.product.domain.rating.RatingInput;

import java.util.List;
import java.util.Optional;

/**
 * What a policy covers over one valid-time segment: the rating inputs it was priced on and the coverage limits
 * that follow from them. Stored whole on every version, so reading the past never needs to replay history.
 */
public record CoverageSnapshot(int productVersion, RatingInput inputs, List<Coverage> coverages) {

    public CoverageSnapshot {
        coverages = List.copyOf(coverages);
    }

    public static CoverageSnapshot of(ProductVersion product, RatingInput inputs) {
        return new CoverageSnapshot(product.version(), inputs,
                product.coverages().stream().map(c -> c.cappedAt(inputs.sumInsuredMinor())).toList());
    }

    public Optional<Coverage> coverage(String code) {
        return coverages.stream().filter(c -> c.code().equals(code)).findFirst();
    }
}
