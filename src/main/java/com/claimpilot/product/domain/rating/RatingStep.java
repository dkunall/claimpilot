package com.claimpilot.product.domain.rating;

/** A link in the factor chain. Steps run in order, each adjusting the premium the previous one left. */
@FunctionalInterface
public interface RatingStep {

    void apply(RatingContext ctx);
}
