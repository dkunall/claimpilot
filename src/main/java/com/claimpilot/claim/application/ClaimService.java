package com.claimpilot.claim.application;

import com.claimpilot.claim.application.ClaimViews.ApproveResult;
import com.claimpilot.claim.application.ClaimViews.AssessCommand;
import com.claimpilot.claim.application.ClaimViews.ClaimView;
import com.claimpilot.claim.application.ClaimViews.DocumentSpec;
import com.claimpilot.claim.application.ClaimViews.DocumentView;
import com.claimpilot.claim.application.ClaimViews.FnolCommand;
import com.claimpilot.claim.application.ClaimViews.FnolResult;
import com.claimpilot.claim.application.ClaimViews.Upload;
import com.claimpilot.claim.domain.ApprovalChain;
import com.claimpilot.claim.domain.ApprovalDecision;
import com.claimpilot.claim.domain.Authority;
import com.claimpilot.claim.domain.Claim;
import com.claimpilot.claim.domain.ClaimDocument;
import com.claimpilot.claim.domain.ClaimStatus;
import com.claimpilot.claim.domain.Settlement;
import com.claimpilot.claim.domain.Transition;
import com.claimpilot.claim.domain.Triage;
import com.claimpilot.claim.domain.TriageResult;
import com.claimpilot.document.application.ObjectStore;
import com.claimpilot.fraud.application.FraudService;
import com.claimpilot.fraud.application.PartyHasher;
import com.claimpilot.fraud.domain.FraudScore;
import com.claimpilot.fraud.domain.PartyRef;
import com.claimpilot.fraud.domain.PartyType;
import com.claimpilot.idempotency.application.IdempotencyService;
import com.claimpilot.idempotency.application.Idempotent;
import com.claimpilot.outbox.application.Outbox;
import com.claimpilot.payment.application.PaymentGateway;
import com.claimpilot.payment.application.PaymentService;
import com.claimpilot.policy.application.PolicyRepository;
import com.claimpilot.policy.application.PolicyService;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.policy.domain.PolicyVersion;
import com.claimpilot.product.application.ProductCatalog;
import com.claimpilot.product.domain.ClaimRules;
import com.claimpilot.product.domain.Coverage;
import com.claimpilot.product.domain.ProductVersion;
import com.claimpilot.shared.config.ClaimPilotProperties;
import com.claimpilot.shared.domain.Times;
import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import com.claimpilot.shared.security.Role;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The claim workflow: first notice of loss, documents, triage (straight-through or not), assessment, the
 * approval chain and settlement. Every step that changes a claim takes its row lock first, so two people acting
 * on one claim at once are serialised rather than racing.
 */
@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);
    private static final Set<String> CONTENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final String AUTO = "system:auto-triage";

    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final PolicyService policyService;
    private final ProductCatalog catalog;
    private final ApprovalLimits limits;
    private final FraudService fraud;
    private final PartyHasher hasher;
    private final ObjectStore store;
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ClaimPilotProperties props;
    private final ApprovalChain chain = ApprovalChain.standard();
    private final MeterRegistry meters;

    public ClaimService(ClaimRepository claims, PolicyRepository policies, PolicyService policyService,
                        ProductCatalog catalog, ApprovalLimits limits, FraudService fraud, PartyHasher hasher,
                        ObjectStore store, PaymentService payments, IdempotencyService idempotency, Outbox outbox,
                        TransactionTemplate tx, Clock clock, ClaimPilotProperties props, MeterRegistry meters) {
        this.claims = claims;
        this.policies = policies;
        this.policyService = policyService;
        this.catalog = catalog;
        this.limits = limits;
        this.fraud = fraud;
        this.hasher = hasher;
        this.store = store;
        this.payments = payments;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.meters = meters;
    }

    // ---------------------------------------------------------------- first notice of loss

    public Idempotent<FnolResult> report(Caller caller, String key, FnolCommand cmd) {
        if (caller.role().worksClaims()) {
            throw ApiException.forbidden("forbidden", "claims are reported by the holder or their agent");
        }
        Policy policy = policyService.visible(caller, cmd.policyId());
        return idempotency.run("fnol", caller, key, cmd, FnolResult.class, () -> fnol(caller, policy, cmd));
    }

    private FnolResult fnol(Caller caller, Policy policy, FnolCommand cmd) {
        Instant now = Times.micros(clock.instant());
        if (cmd.lossAt() == null || cmd.lossAt().isAfter(now)) {
            throw ApiException.invalid("invalid-loss-date", "lossAt is required and can't be in the future");
        }
        if (cmd.claimedMinor() <= 0) {
            throw ApiException.invalid("invalid-amount", "claimedMinor must be positive");
        }
        if (blank(cmd.payoutToken()) || blank(cmd.description()) || cmd.description().length() > 2000) {
            throw ApiException.invalid("invalid-claim", "payoutToken and a description (up to 2000 chars) are required");
        }
        PolicyVersion cover = policyService.coverAt(policy.id(), cmd.lossAt());
        cover.coverage().coverage(cmd.coverageCode()).orElseThrow(() -> ApiException.unprocessable(
                "unknown-coverage", "the policy has no coverage " + cmd.coverageCode() + " at the loss date"));
        ClaimRules rules = product(policy, cover).claimRules();
        List<PartyRef> parties = parties(cmd.parties());
        List<DocumentSpec> specs = cmd.documents() == null ? List.of() : cmd.documents();
        if (specs.size() > 10) {
            throw ApiException.invalid("too-many-documents", "at most 10 documents per claim");
        }
        UUID id = UUID.randomUUID();
        List<ClaimDocument> docs = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            docs.add(document(id, i + 1, specs.get(i), now));
        }
        Claim claim = tx.execute(s -> {
            Claim c = new Claim(id, claims.nextNumber(), policy.id(), cmd.coverageCode(), cover.id(),
                    Times.micros(cmd.lossAt()), now, cmd.claimedMinor(), 0, null, null, ClaimStatus.FNOL, null, null,
                    null, null, null, cmd.payoutToken(), cmd.description().strip(), rules.documentsRequired(),
                    caller.subject(), null, null, 0);
            claims.insert(c);
            claims.insertParties(id, parties);
            claims.insertDocuments(docs);
            claims.transition(id, new Transition(null, ClaimStatus.FNOL, caller.subject(), null, now));
            outbox.append(Outbox.CLAIM_EVENTS, id.toString(), "ClaimReported", Map.of("claimId", id.toString(),
                    "number", c.number(), "policyId", policy.id().toString(), "claimedMinor", c.claimedMinor()));
            return c;
        });
        List<Upload> uploads = docs.stream().map(this::upload).toList();
        if (rules.documentsRequired() == 0) {
            triage(id, AUTO);
        }
        return new FnolResult(view(caller, claim.id(), false), uploads);
    }

    private List<PartyRef> parties(List<ClaimViews.Party> raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > 10) {
            throw ApiException.invalid("too-many-parties", "at most 10 parties per claim");
        }
        Set<PartyRef> out = new LinkedHashSet<>();
        for (ClaimViews.Party p : raw) {
            PartyType type;
            try {
                type = PartyType.valueOf(p.type().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                throw ApiException.invalid("invalid-party", "party type must be one of " + List.of(PartyType.values()));
            }
            if (p.value() == null || p.value().length() > 200) {
                throw ApiException.invalid("invalid-party", "party value is required (up to 200 chars)");
            }
            out.add(hasher.hash(type, p.value()));
        }
        return List.copyOf(out);
    }

    private ClaimDocument document(UUID claimId, int n, DocumentSpec d, Instant now) {
        if (d.contentType() == null || !CONTENT_TYPES.contains(d.contentType())) {
            throw ApiException.invalid("invalid-document", "documents must be one of " + CONTENT_TYPES);
        }
        long max = props.storage().maxDocumentBytes();
        if (d.sizeBytes() <= 0 || d.sizeBytes() > max) {
            throw ApiException.invalid("invalid-document", "document size must be 1.." + max + " bytes");
        }
        String name = d.fileName() == null ? "" : d.fileName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty() || name.length() > 120) {
            throw ApiException.invalid("invalid-document", "fileName is required (up to 120 chars)");
        }
        return new ClaimDocument(UUID.randomUUID(), claimId, "claims/" + claimId + "/" + n + "-" + name, name,
                d.contentType(), d.sizeBytes(), false, now);
    }

    private Upload upload(ClaimDocument d) {
        var p = store.presignPut(d.objectKey(), d.contentType(), d.sizeBytes(), props.storage().presignTtl());
        return new Upload(d.id(), d.fileName(), p.url(), p.headers(), p.expiresAt());
    }

    /** A fresh upload URL, for when the first one expired. */
    public Upload uploadUrl(Caller caller, UUID claimId, UUID documentId) {
        visibleToClaimant(caller, claimId);
        ClaimDocument d = doc(claimId, documentId);
        if (d.uploaded()) {
            throw ApiException.conflict("already-uploaded", "this document is already uploaded");
        }
        return upload(d);
    }

    /** The client says the upload finished; we check the object is really there at the declared size. */
    public ClaimView documentUploaded(Caller caller, UUID claimId, UUID documentId) {
        Claim c = visibleToClaimant(caller, claimId);
        ClaimDocument d = doc(claimId, documentId);
        if (!d.uploaded()) {
            long size = store.sizeOf(d.objectKey()).orElseThrow(() -> ApiException.unprocessable("upload-missing",
                    "nothing has been uploaded for " + d.fileName() + " yet"));
            if (size != d.sizeBytes()) {
                throw ApiException.unprocessable("upload-size-mismatch", "uploaded " + size + " bytes, declared "
                        + d.sizeBytes());
            }
            claims.markUploaded(documentId);
        }
        long uploaded = claims.documents(claimId).stream().filter(ClaimDocument::uploaded).count();
        if (c.status() == ClaimStatus.FNOL && uploaded >= c.documentsRequired()) {
            triage(claimId, AUTO);
        }
        return view(caller, claimId, true);
    }

    private ClaimDocument doc(UUID claimId, UUID documentId) {
        return claims.documents(claimId).stream().filter(d -> d.id().equals(documentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("document"));
    }

    // ---------------------------------------------------------------- triage

    /** Staff can push a claim into triage without waiting for documents; it then can't go straight through. */
    public ClaimView triage(Caller caller, UUID id) {
        caller.require(Role.ASSESSOR, Role.MANAGER);
        find(id);
        triage(id, caller.subject());
        return view(caller, id, true);
    }

    private void triage(UUID id, String actor) {
        Claim done = tx.execute(s -> {
            Claim c = claims.lock(id).orElseThrow(() -> ApiException.notFound("claim"));
            if (c.status() != ClaimStatus.FNOL) {
                return null;
            }
            Instant now = Times.micros(clock.instant());
            Policy p = policies.find(c.policyId()).orElseThrow();
            // the cover as we believed it when the claim was reported, so a later back-dated change can't move it
            PolicyVersion cover = policies.at(p.id(), c.lossAt(), c.reportedAt()).orElseThrow(() ->
                    ApiException.unprocessable("not-covered", "no cover at the loss date"));
            Coverage coverage = cover.coverage().coverage(c.coverageCode()).orElseThrow();
            ClaimRules rules = product(p, cover).claimRules();
            Instant windowStart = c.lossAt().minus(Duration.ofDays(rules.priorClaimWindowDays()));
            long prior = claims.otherClaimsBetween(p.id(), c.id(), windowStart, c.lossAt());
            long docs = claims.documents(id).stream().filter(ClaimDocument::uploaded).count();
            FraudScore score = fraud.addAndScore(p.holderSubject(), claims.parties(id));
            TriageResult result = Triage.decide(new Triage.Facts(c.claimedMinor(), coverage, c.lossAt(),
                    c.reportedAt(), p.firstStart(), prior, docs, score), rules);

            Claim t = c.startTriage();
            claims.transition(id, new Transition(c.status(), t.status(), actor, null, now));
            Claim decided = t.triaged(result, score, now);
            claims.transition(id, new Transition(t.status(), decided.status(), actor,
                    result.stp() ? "straight-through" : String.join("; ", result.reasons()), now));
            if (!result.stp() && result.investigate()) {
                Claim inv = decided.investigate();
                claims.transition(id, new Transition(decided.status(), inv.status(), actor,
                        "fraud score " + score.score(), now));
                decided = inv;
            }
            decided = claims.save(decided);
            outbox.append(Outbox.CLAIM_EVENTS, id.toString(), "ClaimTriaged", Map.of("claimId", id.toString(),
                    "status", decided.status().name(), "reserveMinor", decided.reserveMinor()));
            return decided;
        });
        if (done == null) {
            return;
        }
        String route = switch (done.status()) {
            case STP_APPROVED -> "stp";
            case INVESTIGATION -> "investigation";
            default -> "assessment";
        };
        Counter.builder("claimpilot.claims.triaged").tag("route", route)
                .description("claims through triage, by where they went").register(meters).increment();
        if (done.status() == ClaimStatus.STP_APPROVED) {
            settleQuietly(id, "system:stp");
        }
    }

    // ---------------------------------------------------------------- assessment and approval

    public ClaimView assess(Caller caller, UUID id, AssessCommand cmd) {
        caller.require(Role.ASSESSOR, Role.MANAGER);
        if (cmd.outcome() == null) {
            throw ApiException.invalid("invalid-assessment", "outcome is APPROVE, INVESTIGATE or REJECT");
        }
        tx.executeWithoutResult(s -> {
            Claim c = claims.lock(id).orElseThrow(() -> ApiException.notFound("claim"));
            Instant now = Times.micros(clock.instant());
            Claim a = c.assessed(cmd.assessedMinor(), caller.subject());
            String note = cmd.note() == null ? null : cmd.note().strip();
            Claim next = switch (cmd.outcome()) {
                case APPROVE -> a;
                case INVESTIGATE -> a.status() == ClaimStatus.INVESTIGATION ? a : a.investigate();
                case REJECT -> a.rejected(now);
            };
            claims.transition(id, new Transition(c.status(), next.status(), caller.subject(),
                    "assessed " + cmd.assessedMinor() + (note == null || note.isEmpty() ? "" : ": " + note), now));
            claims.save(next);
            if (next.status() == ClaimStatus.REJECTED) {
                outbox.append(Outbox.CLAIM_EVENTS, id.toString(), "ClaimRejected", Map.of("claimId", id.toString()));
            }
        });
        return view(caller, id, true);
    }

    private record ApproveRequest(UUID claim) {
    }

    public Idempotent<ApproveResult> approve(Caller caller, UUID id, String key) {
        caller.require(Role.ASSESSOR, Role.MANAGER);
        find(id);
        return idempotency.run("approve", caller, key, new ApproveRequest(id), ApproveResult.class,
                () -> signOff(caller, id));
    }

    private ApproveResult signOff(Caller caller, UUID id) {
        Authority authority = caller.is(Role.MANAGER) ? Authority.MANAGER : Authority.ASSESSOR;
        var signoff = new ApprovalChain.Signoff(caller.subject(), authority);
        ApprovalDecision decision = tx.execute(s -> {
            Claim c = claims.lock(id).orElseThrow(() -> ApiException.notFound("claim"));
            if (!c.status().open()) {
                throw ApiException.conflict("not-open", "only a claim in assessment or investigation can be approved;"
                        + " this one is " + c.status());
            }
            if (c.assessedMinor() == null || c.assessedMinor() == 0) {
                throw ApiException.unprocessable("not-assessed", "assess the claim before approving it");
            }
            Policy p = policies.find(c.policyId()).orElseThrow();
            var line = catalog.version(p.productId(), p.productVersion()).line();
            var d = chain.decide(new ApprovalChain.Request(c.assessedMinor(), signoff, c.assessedBy(),
                    claims.approvals(id), limits.forLine(line)));
            Instant now = Times.micros(clock.instant());
            switch (d) {
                case ApprovalDecision.Denied denied -> throw ApiException.forbidden(denied.code(), denied.why());
                case ApprovalDecision.Escalated e -> claims.insertApproval(id, signoff, c.assessedMinor(), now);
                case ApprovalDecision.Approved a -> {
                    claims.insertApproval(id, signoff, c.assessedMinor(), now);
                    Claim ok = c.approved(now);
                    claims.transition(id, new Transition(c.status(), ok.status(), caller.subject(), a.why(), now));
                    claims.save(ok);
                    outbox.append(Outbox.CLAIM_EVENTS, id.toString(), "ClaimApproved", Map.of("claimId",
                            id.toString(), "approvedMinor", ok.approvedMinor()));
                }
            }
            return d;
        });
        return switch (decision) {
            case ApprovalDecision.Approved a -> {
                settleQuietly(id, caller.subject());
                yield new ApproveResult("APPROVED", null, a.why(), view(caller, id, true));
            }
            case ApprovalDecision.Escalated e ->
                    new ApproveResult("ESCALATED", e.needed().name(), e.why(), view(caller, id, true));
            case ApprovalDecision.Denied d -> throw new IllegalStateException("denied is thrown above");
        };
    }

    // ---------------------------------------------------------------- settlement

    /** Retry a payout, optionally to a new destination. Safe to call any number of times. */
    public ClaimView settle(Caller caller, UUID id, String newPayoutToken) {
        caller.require(Role.ASSESSOR, Role.MANAGER);
        if (newPayoutToken != null) {
            tx.executeWithoutResult(s -> claims.save(claims.lock(id).orElseThrow(() -> ApiException.notFound("claim"))
                    .withPayoutToken(newPayoutToken)));
        }
        PaymentGateway.Result r = settleOnce(id, caller.subject());
        if (r != null && !r.approved()) {
            throw new ApiException(402, "payout-declined", r.declineReason());
        }
        return view(caller, id, true);
    }

    private void settleQuietly(UUID id, String actor) {
        try {
            PaymentGateway.Result r = settleOnce(id, actor);
            if (r != null && !r.approved()) {
                log.warn("claim {} payout declined; it stays approved until someone retries", id);
            }
        } catch (RuntimeException e) {
            log.warn("claim {} settlement failed; it stays approved until someone retries: {}", id, e.toString());
        }
    }

    /**
     * Pays out with a gateway key derived from the claim, so a retry after a crash between the payout and our
     * commit gets the gateway's original answer instead of paying twice. Returns null if it was already settled.
     */
    private PaymentGateway.Result settleOnce(UUID id, String actor) {
        Claim c = find(id);
        if (c.status() == ClaimStatus.SETTLED) {
            return null;
        }
        if (!c.status().payable()) {
            throw ApiException.conflict("not-payable", "the claim is " + c.status() + ", not approved");
        }
        PaymentGateway.Result r = payments.payout("settle:" + id, c.payoutToken(), c.approvedMinor());
        if (!r.approved()) {
            return r;
        }
        Claim settled = tx.execute(s -> {
            Claim locked = claims.lock(id).orElseThrow();
            if (locked.status() == ClaimStatus.SETTLED) {
                return null;
            }
            Instant now = Times.micros(clock.instant());
            if (!claims.insertSettlement(new Settlement(id, locked.approvedMinor(), r.reference(), actor, now))) {
                return null;
            }
            Claim done = locked.settled(now);
            claims.transition(id, new Transition(locked.status(), done.status(), actor, "paid " + r.reference(), now));
            claims.save(done);
            outbox.append(Outbox.CLAIM_EVENTS, id.toString(), "ClaimSettled", Map.of("claimId", id.toString(),
                    "number", done.number(), "amountMinor", done.approvedMinor(), "policyId",
                    done.policyId().toString()));
            return done;
        });
        if (settled != null) {
            Timer.builder("claimpilot.claims.cycle_time").tag("route", Boolean.TRUE.equals(settled.stpEligible())
                            ? "stp" : "manual").description("report to settlement").publishPercentileHistogram()
                    .register(meters).record(Duration.between(settled.reportedAt(), settled.settledAt()));
        }
        return r;
    }

    // ---------------------------------------------------------------- reads

    public ClaimView get(Caller caller, UUID id) {
        return view(caller, id, true);
    }

    public List<ClaimView> list(Caller caller, ClaimStatus status) {
        List<Claim> found = caller.role().worksClaims() ? claims.list(status, 200)
                : claims.forSubject(caller.subject(), status, 200);
        return found.stream().map(c -> summary(caller, c)).toList();
    }

    private ClaimView view(Caller caller, UUID id, boolean check) {
        Claim c = check ? visible(caller, id) : find(id);
        List<DocumentView> docs = claims.documents(id).stream().map(d -> new DocumentView(d.id(), d.fileName(),
                d.contentType(), d.sizeBytes(), d.uploaded())).toList();
        List<ClaimViews.Approval> approvals = claims.approvals(id).stream()
                .map(a -> new ClaimViews.Approval(a.approver(), a.authority().name())).toList();
        return build(caller, c, docs, claims.transitions(id), approvals, claims.settlement(id).orElse(null));
    }

    private ClaimView summary(Caller caller, Claim c) {
        return build(caller, c, null, null, null, null);
    }

    private static ClaimView build(Caller caller, Claim c, List<DocumentView> docs, List<Transition> history,
                                   List<ClaimViews.Approval> approvals, Settlement settlement) {
        boolean staff = caller.role().worksClaims();
        return new ClaimView(c.id(), c.number(), c.policyId(), c.coverageCode(), c.lossAt(), c.reportedAt(),
                c.claimedMinor(), c.reserveMinor(), c.assessedMinor(), c.approvedMinor(), c.status(),
                c.stpEligible(), c.triageReasons(), staff ? c.fraudScore() : null,
                staff ? c.fraudReasons() : null, staff ? c.assessedBy() : null, c.documentsRequired(),
                c.decidedAt(), c.settledAt(), c.lockVersion(), docs, history, staff ? approvals : null, settlement);
    }

    private Claim find(UUID id) {
        return claims.find(id).orElseThrow(() -> ApiException.notFound("claim"));
    }

    /** Staff see every claim; holders and agents the ones on policies they can see; others get 404. */
    private Claim visible(Caller caller, UUID id) {
        Claim c = find(id);
        if (caller.role().worksClaims()) {
            return c;
        }
        Optional<Policy> p = policies.find(c.policyId());
        if (p.isEmpty() || !caller.canSee(p.get().holderSubject(), p.get().agentSubject())) {
            throw ApiException.notFound("claim");
        }
        return c;
    }

    private Claim visibleToClaimant(Caller caller, UUID id) {
        if (caller.role().worksClaims()) {
            throw ApiException.forbidden("forbidden", "documents are uploaded by the claimant");
        }
        return visible(caller, id);
    }

    private ProductVersion product(Policy p, PolicyVersion cover) {
        return catalog.version(p.productId(), cover.coverage().productVersion());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
