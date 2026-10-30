package com.claimpilot.payment.adapter;

import com.claimpilot.payment.application.PaymentRepository;
import com.claimpilot.payment.domain.Payment;
import com.claimpilot.payment.domain.PaymentKind;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPaymentRepository implements PaymentRepository {

    private static final String COLS =
            "id, policy_id, kind, period_start, amount_minor, status, gateway_ref, gateway_key, receipt_no, created_at";
    private static final RowMapper<Payment> MAPPER = (rs, i) -> new Payment(rs.getObject("id", UUID.class),
            rs.getObject("policy_id", UUID.class), PaymentKind.valueOf(rs.getString("kind")),
            rs.getTimestamp("period_start").toInstant(), rs.getLong("amount_minor"),
            "SUCCEEDED".equals(rs.getString("status")), rs.getString("gateway_ref"), rs.getString("gateway_key"),
            rs.getString("receipt_no"), rs.getTimestamp("created_at").toInstant());

    private final JdbcClient db;

    JdbcPaymentRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public Payment insertOrGet(Payment p) {
        db.sql("insert into payment (" + COLS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (gateway_key) do nothing")
                .params(p.id(), p.policyId(), p.kind().name(), Timestamp.from(p.periodStart()), p.amountMinor(),
                        p.succeeded() ? "SUCCEEDED" : "DECLINED", p.gatewayRef(), p.gatewayKey(), p.receiptNo(),
                        Timestamp.from(p.createdAt()))
                .update();
        return db.sql("select " + COLS + " from payment where gateway_key = ?").param(p.gatewayKey()).query(MAPPER).single();
    }

    @Override
    public Optional<Payment> find(UUID id) {
        return db.sql("select " + COLS + " from payment where id = ?").param(id).query(MAPPER).optional();
    }

    @Override
    public List<Payment> forPolicy(UUID policyId) {
        return db.sql("select " + COLS + " from payment where policy_id = ? order by created_at, id")
                .param(policyId).query(MAPPER).list();
    }

    @Override
    public String nextReceiptNumber() {
        long n = db.sql("select nextval('receipt_seq')").query(Long.class).single();
        return String.format("RCPT-%08d", n);
    }
}
