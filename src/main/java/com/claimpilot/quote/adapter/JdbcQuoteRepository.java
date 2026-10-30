package com.claimpilot.quote.adapter;

import com.claimpilot.product.domain.rating.Premium;
import com.claimpilot.product.domain.rating.RatingInput;
import com.claimpilot.quote.application.QuoteRepository;
import com.claimpilot.quote.domain.Quote;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcQuoteRepository implements QuoteRepository {

    private final JdbcClient db;
    private final ObjectMapper json;

    JdbcQuoteRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public void insert(Quote q) {
        db.sql("""
                insert into quote (id, product_id, product_version, holder_subject, agent_subject, holder_name,
                    holder_email, inputs, premium, total_minor, status, policy_id, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)""")
                .params(q.id(), q.productId(), q.productVersion(), q.holderSubject(), q.agentSubject(), q.holderName(),
                        q.holderEmail(), write(q.inputs()), write(q.premium()), q.premium().totalMinor(),
                        q.status().name(), q.policyId(), Timestamp.from(q.createdAt()), Timestamp.from(q.expiresAt()))
                .update();
    }

    @Override
    public Optional<Quote> find(UUID id) {
        return db.sql("select * from quote where id = ?").param(id).query(this::row).optional();
    }

    @Override
    public Optional<Quote> lock(UUID id) {
        return db.sql("select * from quote where id = ? for update").param(id).query(this::row).optional();
    }

    @Override
    public void markBound(UUID id, UUID policyId) {
        db.sql("update quote set status = 'BOUND', policy_id = ? where id = ?").params(policyId, id).update();
    }

    private Quote row(ResultSet rs, int i) throws SQLException {
        try {
            return new Quote(rs.getObject("id", UUID.class), rs.getString("product_id"), rs.getInt("product_version"),
                    rs.getString("holder_subject"), rs.getString("agent_subject"), rs.getString("holder_name"),
                    rs.getString("holder_email"), json.readValue(rs.getString("inputs"), RatingInput.class),
                    json.readValue(rs.getString("premium"), Premium.class),
                    Quote.QuoteStatus.valueOf(rs.getString("status")), rs.getObject("policy_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
        } catch (JsonProcessingException e) {
            throw new SQLException("bad quote json", e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
