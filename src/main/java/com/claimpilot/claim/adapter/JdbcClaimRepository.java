package com.claimpilot.claim.adapter;

import com.claimpilot.claim.application.ClaimRepository;
import com.claimpilot.claim.domain.ApprovalChain;
import com.claimpilot.claim.domain.Authority;
import com.claimpilot.claim.domain.Claim;
import com.claimpilot.claim.domain.ClaimDocument;
import com.claimpilot.claim.domain.ClaimStatus;
import com.claimpilot.claim.domain.Settlement;
import com.claimpilot.claim.domain.Transition;
import com.claimpilot.fraud.domain.PartyRef;
import com.claimpilot.fraud.domain.PartyType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
class JdbcClaimRepository implements ClaimRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient db;
    private final ObjectMapper json;
    private final RowMapper<Claim> claimRow = this::claim;

    JdbcClaimRepository(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public String nextNumber() {
        long n = db.sql("select nextval('claim_seq')").query(Long.class).single();
        return "CL-%07d".formatted(n);
    }

    @Override
    public void insert(Claim c) {
        db.sql("""
                insert into claim (id, number, policy_id, coverage_code, coverage_version_id, loss_date, reported_at,
                    claimed_minor, reserve_minor, status, payout_token, description, documents_required, created_by,
                    lock_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""")
                .params(c.id(), c.number(), c.policyId(), c.coverageCode(), c.coverageVersionId(), ts(c.lossAt()),
                        ts(c.reportedAt()), c.claimedMinor(), c.reserveMinor(), c.status().name(), c.payoutToken(),
                        c.description(), c.documentsRequired(), c.createdBy())
                .update();
    }

    @Override
    public Optional<Claim> find(UUID id) {
        return db.sql("select * from claim where id = ?").param(id).query(claimRow).optional();
    }

    @Override
    public Optional<Claim> lock(UUID id) {
        return db.sql("select * from claim where id = ? for update").param(id).query(claimRow).optional();
    }

    @Override
    public Claim save(Claim c) {
        int n = db.sql("""
                update claim set reserve_minor = ?, assessed_minor = ?, approved_minor = ?, status = ?,
                    stp_eligible = ?, triage_reasons = ?::jsonb, fraud_score = ?, fraud_reasons = ?::jsonb,
                    assessed_by = ?, payout_token = ?, decided_at = ?, settled_at = ?, lock_version = lock_version + 1
                where id = ? and lock_version = ?""")
                .params(c.reserveMinor(), c.assessedMinor(), c.approvedMinor(), c.status().name(), c.stpEligible(),
                        write(c.triageReasons()), c.fraudScore(), write(c.fraudReasons()), c.assessedBy(),
                        c.payoutToken(), ts(c.decidedAt()), ts(c.settledAt()), c.id(), c.lockVersion())
                .update();
        if (n != 1) {
            // every caller holds the row lock, so this means a caller forgot to take it
            throw new IllegalStateException("claim " + c.id() + " changed underneath a locked update");
        }
        return find(c.id()).orElseThrow();
    }

    @Override
    public List<Claim> list(ClaimStatus status, int limit) {
        return db.sql("select * from claim where (cast(? as text) is null or status = ?) order by reported_at desc limit ?")
                .params(status == null ? null : status.name(), status == null ? null : status.name(), limit)
                .query(claimRow).list();
    }

    @Override
    public List<Claim> forSubject(String subject, ClaimStatus status, int limit) {
        return db.sql("""
                select c.* from claim c join policy p on p.id = c.policy_id
                where (p.holder_subject = ? or p.agent_subject = ?) and (cast(? as text) is null or c.status = ?)
                order by c.reported_at desc limit ?""")
                .params(subject, subject, status == null ? null : status.name(), status == null ? null : status.name(),
                        limit)
                .query(claimRow).list();
    }

    @Override
    public long otherClaimsBetween(UUID policyId, UUID exclude, Instant from, Instant to) {
        return db.sql("""
                select count(*) from claim where policy_id = ? and id <> ? and status <> 'REJECTED'
                    and loss_date >= ? and loss_date <= ?""")
                .params(policyId, exclude, ts(from), ts(to)).query(Long.class).single();
    }

    @Override
    public void insertParties(UUID claimId, List<PartyRef> parties) {
        for (PartyRef p : parties) {
            db.sql("insert into claim_party (claim_id, party_type, party_key_hash) values (?, ?, ?) on conflict do nothing")
                    .params(claimId, p.type().name(), p.hash()).update();
        }
    }

    @Override
    public List<PartyRef> parties(UUID claimId) {
        return db.sql("select party_type, party_key_hash from claim_party where claim_id = ?").param(claimId)
                .query((rs, i) -> new PartyRef(PartyType.valueOf(rs.getString(1)), rs.getString(2))).list();
    }

    @Override
    public void insertDocuments(List<ClaimDocument> docs) {
        for (ClaimDocument d : docs) {
            db.sql("""
                    insert into claim_document (id, claim_id, object_key, file_name, content_type, size_bytes, status,
                        created_at) values (?, ?, ?, ?, ?, ?, 'PENDING', ?)""")
                    .params(d.id(), d.claimId(), d.objectKey(), d.fileName(), d.contentType(), d.sizeBytes(),
                            ts(d.createdAt()))
                    .update();
        }
    }

    @Override
    public List<ClaimDocument> documents(UUID claimId) {
        return db.sql("select * from claim_document where claim_id = ? order by object_key").param(claimId)
                .query((rs, i) -> new ClaimDocument(rs.getObject("id", UUID.class), claimId,
                        rs.getString("object_key"), rs.getString("file_name"), rs.getString("content_type"),
                        rs.getLong("size_bytes"), "UPLOADED".equals(rs.getString("status")),
                        instant(rs, "created_at")))
                .list();
    }

    @Override
    public boolean markUploaded(UUID documentId) {
        return db.sql("update claim_document set status = 'UPLOADED' where id = ? and status = 'PENDING'")
                .param(documentId).update() == 1;
    }

    @Override
    public void transition(UUID claimId, Transition t) {
        db.sql("insert into claim_transition (claim_id, from_status, to_status, actor, note, at) values (?, ?, ?, ?, ?, ?)")
                .params(claimId, t.from() == null ? null : t.from().name(), t.to().name(), t.actor(), t.note(),
                        ts(t.at()))
                .update();
    }

    @Override
    public List<Transition> transitions(UUID claimId) {
        return db.sql("select * from claim_transition where claim_id = ? order by id").param(claimId)
                .query((rs, i) -> new Transition(
                        rs.getString("from_status") == null ? null : ClaimStatus.valueOf(rs.getString("from_status")),
                        ClaimStatus.valueOf(rs.getString("to_status")), rs.getString("actor"), rs.getString("note"),
                        instant(rs, "at")))
                .list();
    }

    @Override
    public List<ApprovalChain.Signoff> approvals(UUID claimId) {
        return db.sql("select approver, role from claim_approval where claim_id = ? order by at, approver")
                .param(claimId)
                .query((rs, i) -> new ApprovalChain.Signoff(rs.getString(1), Authority.valueOf(rs.getString(2))))
                .list();
    }

    @Override
    public void insertApproval(UUID claimId, ApprovalChain.Signoff s, long amountMinor, Instant at) {
        db.sql("""
                insert into claim_approval (claim_id, approver, role, amount_minor, at) values (?, ?, ?, ?, ?)
                on conflict do nothing""")
                .params(claimId, s.approver(), s.authority().name(), amountMinor, ts(at)).update();
    }

    @Override
    public boolean insertSettlement(Settlement s) {
        return db.sql("""
                insert into settlement (claim_id, amount_minor, gateway_ref, settled_by, at) values (?, ?, ?, ?, ?)
                on conflict (claim_id) do nothing""")
                .params(s.claimId(), s.amountMinor(), s.gatewayRef(), s.settledBy(), ts(s.at())).update() == 1;
    }

    @Override
    public Optional<Settlement> settlement(UUID claimId) {
        return db.sql("select * from settlement where claim_id = ?").param(claimId)
                .query((rs, i) -> new Settlement(claimId, rs.getLong("amount_minor"), rs.getString("gateway_ref"),
                        rs.getString("settled_by"), instant(rs, "at")))
                .optional();
    }

    private Claim claim(ResultSet rs, int i) throws SQLException {
        return new Claim(rs.getObject("id", UUID.class), rs.getString("number"), rs.getObject("policy_id", UUID.class),
                rs.getString("coverage_code"), (Long) rs.getObject("coverage_version_id"), instant(rs, "loss_date"),
                instant(rs, "reported_at"), rs.getLong("claimed_minor"), rs.getLong("reserve_minor"),
                (Long) rs.getObject("assessed_minor"), (Long) rs.getObject("approved_minor"),
                ClaimStatus.valueOf(rs.getString("status")), (Boolean) rs.getObject("stp_eligible"),
                read(rs.getString("triage_reasons")), (Double) rs.getObject("fraud_score"),
                read(rs.getString("fraud_reasons")), rs.getString("assessed_by"), rs.getString("payout_token"),
                rs.getString("description"), rs.getInt("documents_required"), rs.getString("created_by"),
                instant(rs, "decided_at"), instant(rs, "settled_at"), rs.getLong("lock_version"));
    }

    private String write(List<String> v) {
        try {
            return v == null ? null : json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> read(String v) {
        try {
            return v == null ? List.of() : json.readValue(v, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Timestamp ts(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
