package com.claimpilot.policy.adapter;

import com.claimpilot.policy.application.PolicyRepository;
import com.claimpilot.policy.domain.CoverageSnapshot;
import com.claimpilot.policy.domain.EndorsementChange;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.policy.domain.PolicyStatus;
import com.claimpilot.policy.domain.PolicyVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPolicyRepository implements PolicyRepository {

    private static final String VERSION_COLS = """
            id, lower(valid) vf, upper(valid) vt, lower(recorded) rf, upper(recorded) rt, coverage::text,
            annual_premium_minor, reason, endorsement_id""";

    private final JdbcClient db;
    private final ObjectMapper json;
    private final RowMapper<Policy> policyRow = this::policy;
    private final RowMapper<PolicyVersion> versionRow = this::version;

    JdbcPolicyRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public void insert(Policy p) {
        db.sql("""
                insert into policy (id, number, holder_subject, agent_subject, holder_name, holder_email, product_id,
                    product_version, status, first_start, term_start, term_end, grace_until, autopay_token,
                    lock_version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(p.id(), p.number(), p.holderSubject(), p.agentSubject(), p.holderName(), p.holderEmail(),
                        p.productId(), p.productVersion(), p.status().name(), ts(p.firstStart()), ts(p.termStart()),
                        ts(p.termEnd()), ts(p.graceUntil()), p.autopayToken(), p.lockVersion(), ts(p.createdAt()))
                .update();
    }

    @Override
    public Optional<Policy> find(UUID id) {
        return db.sql("select * from policy where id = ?").param(id).query(policyRow).optional();
    }

    @Override
    public List<Policy> forSubject(String subject) {
        return db.sql("select * from policy where holder_subject = ? or agent_subject = ? order by created_at desc limit 200")
                .params(subject, subject).query(policyRow).list();
    }

    @Override
    public List<PolicyVersion> current(UUID policyId) {
        return db.sql("select " + VERSION_COLS + " from policy_version where policy_id = ? and upper_inf(recorded) order by lower(valid)")
                .param(policyId).query(versionRow).list();
    }

    @Override
    public Optional<PolicyVersion> at(UUID policyId, Instant validAt, Instant asOf) {
        return db.sql("select " + VERSION_COLS + """
                         from policy_version where policy_id = ? and valid @> ?::timestamptz and recorded @> ?::timestamptz
                        """)
                .params(policyId, ts(validAt), ts(asOf)).query(versionRow).optional();
    }

    @Override
    public List<PolicyVersion> history(UUID policyId) {
        return db.sql("select " + VERSION_COLS + " from policy_version where policy_id = ? order by lower(recorded), lower(valid)")
                .param(policyId).query(versionRow).list();
    }

    @Override
    public boolean bump(UUID id, long expected) {
        return db.sql("update policy set lock_version = lock_version + 1 where id = ? and lock_version = ?")
                .params(id, expected).update() == 1;
    }

    @Override
    public boolean close(List<Long> ids, Instant recordedAt) {
        if (ids.isEmpty()) {
            return true;
        }
        int n = db.sql("""
                        update policy_version set recorded = tstzrange(lower(recorded), :at, '[)')
                        where id in (:ids) and upper_inf(recorded)""")
                .param("at", ts(recordedAt)).param("ids", ids).update();
        return n == ids.size();
    }

    @Override
    public void insertVersions(UUID policyId, List<PolicyVersion> rows) {
        for (PolicyVersion v : rows) {
            db.sql("""
                    insert into policy_version (policy_id, valid, recorded, product_version, coverage,
                        annual_premium_minor, reason, endorsement_id)
                    values (?, tstzrange(?, ?, '[)'), tstzrange(?, null, '[)'), ?, ?::jsonb, ?, ?, ?)""")
                    .params(policyId, ts(v.validFrom()), ts(v.validTo()), ts(v.recordedFrom()),
                            v.coverage().productVersion(), write(v.coverage()), v.annualPremiumMinor(), v.reason(),
                            v.endorsementId())
                    .update();
        }
    }

    @Override
    public void insertEndorsement(UUID id, UUID policyId, Instant effectiveFrom, EndorsementChange change,
                                  long premiumDeltaMinor, Instant recordedAt, String createdBy) {
        db.sql("""
                insert into endorsement (id, policy_id, effective_from, change, premium_delta_minor, recorded_at, created_by)
                values (?, ?, ?, ?::jsonb, ?, ?, ?)""")
                .params(id, policyId, ts(effectiveFrom), write(change), premiumDeltaMinor, ts(recordedAt), createdBy)
                .update();
    }

    @Override
    public void setStatus(UUID id, PolicyStatus status, Instant graceUntil) {
        db.sql("update policy set status = ?, grace_until = ? where id = ?")
                .params(status.name(), ts(graceUntil), id).update();
    }

    @Override
    public Optional<Policy> lock(UUID id) {
        return db.sql("select * from policy where id = ? for update").param(id).query(policyRow).optional();
    }

    @Override
    public void startTerm(UUID id, int productVersion, Instant termStart, Instant termEnd, PolicyStatus status,
                          Instant graceUntil) {
        db.sql("""
                update policy set product_version = ?, term_start = ?, term_end = ?, status = ?, grace_until = ?,
                    lock_version = lock_version + 1
                where id = ?""")
                .params(productVersion, ts(termStart), ts(termEnd), status.name(), ts(graceUntil), id).update();
    }

    private Policy policy(ResultSet rs, int i) throws SQLException {
        return new Policy(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("holder_subject"),
                rs.getString("agent_subject"), rs.getString("holder_name"), rs.getString("holder_email"),
                rs.getString("product_id"), rs.getInt("product_version"), PolicyStatus.valueOf(rs.getString("status")),
                instant(rs, "first_start"), instant(rs, "term_start"), instant(rs, "term_end"),
                instant(rs, "grace_until"), rs.getString("autopay_token"), rs.getLong("lock_version"),
                instant(rs, "created_at"));
    }

    private PolicyVersion version(ResultSet rs, int i) throws SQLException {
        CoverageSnapshot snap;
        try {
            snap = json.readValue(rs.getString(6), CoverageSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("bad coverage json", e);
        }
        return new PolicyVersion(rs.getLong(1), instant(rs, "vf"), instant(rs, "vt"), instant(rs, "rf"),
                instant(rs, "rt"), snap, rs.getLong(7), rs.getString(8), rs.getObject(9, UUID.class));
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Timestamp ts(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
