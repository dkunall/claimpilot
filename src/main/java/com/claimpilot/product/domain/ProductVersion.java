package com.claimpilot.product.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** An immutable product definition. Policies keep the version they were priced with. */
public record ProductVersion(
        String productId,
        int version,
        LineOfBusiness line,
        String name,
        List<Coverage> coverages,
        List<Rider> riders,
        RatingTable rating,
        ClaimRules claimRules,
        Instant createdAt) {

    public ProductVersion {
        if (productId == null || !productId.matches("[a-z][a-z0-9-]{1,39}")) {
            throw new InvalidProductException("product id must be lower-case kebab, 2-40 chars");
        }
        Checks.text(name, "product name");
        if (line == null || rating == null || claimRules == null) {
            throw new InvalidProductException("line, rating and claim rules are required");
        }
        coverages = List.copyOf(coverages == null ? List.of() : coverages);
        riders = List.copyOf(riders == null ? List.of() : riders);
        if (coverages.isEmpty()) {
            throw new InvalidProductException("a product needs at least one coverage");
        }
        unique(coverages.stream().map(Coverage::code).toList(), "coverage");
        unique(riders.stream().map(Rider::code).toList(), "rider");
    }

    public Optional<Rider> rider(String code) {
        return riders.stream().filter(r -> r.code().equals(code)).findFirst();
    }

    public ProductVersion withVersion(int next, Instant at) {
        return new ProductVersion(productId, next, line, name, coverages, riders, rating, claimRules, at);
    }

    private static void unique(List<String> codes, String what) {
        Set<String> seen = new HashSet<>();
        for (String c : codes) {
            if (!seen.add(c)) {
                throw new InvalidProductException("duplicate " + what + " code " + c);
            }
        }
    }
}
