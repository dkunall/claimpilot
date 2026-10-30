package com.claimpilot.product.adapter;

import com.claimpilot.product.application.ProductCatalog;
import com.claimpilot.product.domain.ClaimRules;
import com.claimpilot.product.domain.Coverage;
import com.claimpilot.product.domain.InvalidProductException;
import com.claimpilot.product.domain.LineOfBusiness;
import com.claimpilot.product.domain.ProductVersion;
import com.claimpilot.product.domain.RatingTable;
import com.claimpilot.product.domain.Rider;
import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class ProductController {

    private final ProductCatalog catalog;

    ProductController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    record ProductDraft(String productId, LineOfBusiness line, String name, List<Coverage> coverages,
                        List<Rider> riders, RatingTable rating, ClaimRules claimRules) {
    }

    @GetMapping("/v1/products")
    List<ProductVersion> list() {
        return catalog.list();
    }

    @GetMapping("/v1/products/{id}")
    ProductVersion latest(@PathVariable String id) {
        return catalog.latest(id);
    }

    @GetMapping("/v1/products/{id}/versions/{version}")
    ProductVersion version(@PathVariable String id, @PathVariable int version) {
        return catalog.version(id, version);
    }

    @PostMapping("/v1/products")
    @ResponseStatus(HttpStatus.CREATED)
    ProductVersion publish(Caller caller, @RequestBody ProductDraft d) {
        ProductVersion draft;
        try {
            draft = new ProductVersion(d.productId(), 1, d.line(), d.name(), d.coverages(), d.riders(), d.rating(),
                    d.claimRules(), java.time.Instant.EPOCH);
        } catch (InvalidProductException e) {
            throw ApiException.invalid("invalid-product", e.getMessage());
        }
        return catalog.publish(caller, draft);
    }
}
