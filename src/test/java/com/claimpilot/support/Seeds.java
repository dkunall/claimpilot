package com.claimpilot.support;

import com.claimpilot.product.domain.ProductVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** The same synthetic products the app seeds, for unit tests. */
public final class Seeds {

    private static final List<ProductVersion> PRODUCTS = load();

    private Seeds() {
    }

    public static ProductVersion health() {
        return PRODUCTS.stream().filter(p -> p.productId().equals("health-shield")).findFirst().orElseThrow();
    }

    public static ProductVersion motor() {
        return PRODUCTS.stream().filter(p -> p.productId().equals("motor-secure")).findFirst().orElseThrow();
    }

    private static List<ProductVersion> load() {
        try (InputStream in = Seeds.class.getResourceAsStream("/seed/products.json")) {
            return new ObjectMapper().findAndRegisterModules().readValue(in, new TypeReference<>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
