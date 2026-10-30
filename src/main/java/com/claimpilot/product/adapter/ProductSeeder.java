package com.claimpilot.product.adapter;

import com.claimpilot.product.application.ProductCatalog;
import com.claimpilot.product.domain.ProductVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Publishes the two synthetic starter products on first boot so a fresh install can quote straight away. */
@Component
@Order(0)
class ProductSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductSeeder.class);

    private final ProductCatalog catalog;
    private final ObjectMapper json;

    ProductSeeder(ProductCatalog catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        try (InputStream in = new ClassPathResource("seed/products.json").getInputStream()) {
            List<ProductVersion> seeds = json.readValue(in, new TypeReference<>() { });
            for (ProductVersion p : seeds) {
                if (catalog.seed(p)) {
                    log.info("seeded product {} v1", p.productId());
                }
            }
        }
    }
}
