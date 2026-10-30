package com.claimpilot.product.adapter;

import com.claimpilot.product.application.ProductRepository;
import com.claimpilot.product.domain.ClaimRules;
import com.claimpilot.product.domain.Coverage;
import com.claimpilot.product.domain.LineOfBusiness;
import com.claimpilot.product.domain.ProductVersion;
import com.claimpilot.product.domain.RatingTable;
import com.claimpilot.product.domain.Rider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

@Repository
class JdbcProductRepository implements ProductRepository {

    private static final String COLS = "product_id, version, line, name, coverages, riders, rating, claim_rules, created_at";

    private final JdbcClient db;
    private final ObjectMapper json;
    private final RowMapper<ProductVersion> mapper;

    JdbcProductRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
        this.mapper = (rs, i) -> new ProductVersion(
                rs.getString("product_id"), rs.getInt("version"), LineOfBusiness.valueOf(rs.getString("line")),
                rs.getString("name"),
                read(rs.getString("coverages"), new TypeReference<List<Coverage>>() { }),
                read(rs.getString("riders"), new TypeReference<List<Rider>>() { }),
                read(rs.getString("rating"), new TypeReference<RatingTable>() { }),
                read(rs.getString("claim_rules"), new TypeReference<ClaimRules>() { }),
                rs.getTimestamp("created_at").toInstant());
    }

    @Override
    public boolean insert(ProductVersion p, String createdBy) {
        return db.sql("""
                        insert into product_version (product_id, version, line, name, coverages, riders, rating,
                                                     claim_rules, created_by, created_at)
                        values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                        on conflict do nothing""")
                .params(p.productId(), p.version(), p.line().name(), p.name(), write(p.coverages()), write(p.riders()),
                        write(p.rating()), write(p.claimRules()), createdBy, java.sql.Timestamp.from(p.createdAt()))
                .update() == 1;
    }

    @Override
    public Optional<ProductVersion> find(String productId, int version) {
        return db.sql("select " + COLS + " from product_version where product_id = ? and version = ?")
                .params(productId, version).query(mapper).optional();
    }

    @Override
    public Optional<ProductVersion> latest(String productId) {
        return db.sql("select " + COLS + " from product_version where product_id = ? order by version desc limit 1")
                .param(productId).query(mapper).optional();
    }

    @Override
    public List<ProductVersion> latestOfEach() {
        return db.sql("select distinct on (product_id) " + COLS
                + " from product_version order by product_id, version desc").query(mapper).list();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T read(String s, TypeReference<T> type) {
        try {
            return json.readValue(s, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
