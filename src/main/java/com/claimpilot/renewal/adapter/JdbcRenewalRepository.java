package com.claimpilot.renewal.adapter;

import com.claimpilot.renewal.application.RenewalRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcRenewalRepository implements RenewalRepository {

    private final JdbcClient db;

    JdbcRenewalRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean insert(Renewal r) {
        return db.sql("""
                        insert into renewal (policy_id, period_start, status, product_version, premium_minor, payment_id,
                            job_execution_id, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (policy_id, period_start) do nothing""")
                .params(r.policyId(), ts(r.periodStart()), r.status().name(), r.productVersion(), r.premiumMinor(),
                        r.paymentId(), r.jobExecutionId(), ts(r.createdAt()))
                .update() == 1;
    }

    @Override
    public Optional<Renewal> find(UUID policyId, Instant periodStart) {
        return db.sql("select * from renewal where policy_id = ? and period_start = ?")
                .params(policyId, ts(periodStart)).query(this::row).optional();
    }

    @Override
    public void markPaid(UUID policyId, Instant periodStart, UUID paymentId) {
        db.sql("update renewal set status = 'RENEWED', payment_id = ? where policy_id = ? and period_start = ?")
                .params(paymentId, policyId, ts(periodStart)).update();
    }

    @Override
    public List<Renewal> forPolicy(UUID policyId) {
        return db.sql("select * from renewal where policy_id = ? order by period_start")
                .param(policyId).query(this::row).list();
    }

    @Override
    public Map<Status, Long> countByExecution(long jobExecutionId) {
        Map<Status, Long> out = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            out.put(s, 0L);
        }
        db.sql("select status, count(*) from renewal where job_execution_id = ? group by status")
                .param(jobExecutionId)
                .query((rs, i) -> out.put(Status.valueOf(rs.getString(1)), rs.getLong(2))).list();
        return out;
    }

    @Override
    public List<UUID> graceExpired(Instant runAt) {
        return db.sql("select id from policy where status = 'GRACE' and grace_until <= ? order by seq")
                .param(ts(runAt)).query(UUID.class).list();
    }

    @Override
    public List<ReminderDue> remindersDue(Instant from, Instant to) {
        return db.sql("""
                        select p.id, p.number, p.term_end, p.autopay_token is not null as autopay from policy p
                        where p.status = 'ACTIVE' and p.term_end > ? and p.term_end <= ?
                          and not exists (select 1 from renewal_reminder r where r.policy_id = p.id and r.term_end = p.term_end)
                        order by p.seq""")
                .params(ts(from), ts(to))
                .query((rs, i) -> new ReminderDue(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getBoolean(4)))
                .list();
    }

    @Override
    public boolean insertReminder(UUID policyId, Instant termEnd, Instant at) {
        return db.sql("insert into renewal_reminder (policy_id, term_end, sent_at) values (?, ?, ?) on conflict do nothing")
                .params(policyId, ts(termEnd), ts(at)).update() == 1;
    }

    private Renewal row(ResultSet rs, int i) throws SQLException {
        long job = rs.getLong("job_execution_id");
        Long jobId = rs.wasNull() ? null : job;
        return new Renewal(rs.getObject("policy_id", UUID.class), rs.getTimestamp("period_start").toInstant(),
                Status.valueOf(rs.getString("status")), rs.getInt("product_version"), rs.getLong("premium_minor"),
                rs.getObject("payment_id", UUID.class), jobId,
                rs.getTimestamp("created_at").toInstant());
    }

    private static Timestamp ts(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }
}
