package com.claimpilot.policy.application;

import com.claimpilot.idempotency.application.IdempotencyService;
import com.claimpilot.idempotency.application.Idempotent;
import com.claimpilot.outbox.application.Outbox;
import com.claimpilot.payment.application.PaymentGateway;
import com.claimpilot.payment.application.PaymentService;
import com.claimpilot.payment.domain.Payment;
import com.claimpilot.payment.domain.PaymentKind;
import com.claimpilot.policy.application.PolicyViews.EndorsementResult;
import com.claimpilot.policy.application.PolicyViews.History;
import com.claimpilot.policy.application.PolicyViews.PolicyView;
import com.claimpilot.policy.application.PolicyViews.VersionView;
import com.claimpilot.policy.domain.CoverageSnapshot;
import com.claimpilot.policy.domain.EndorsementChange;
import com.claimpilot.policy.domain.EndorsementPlan;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.policy.domain.PolicyStatus;
import com.claimpilot.policy.domain.PolicyVersion;
import com.claimpilot.policy.domain.Reprice;
import com.claimpilot.product.application.ProductCatalog;
import com.claimpilot.shared.domain.Times;
import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import com.claimpilot.shared.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads policies at any (valid time, record time) and applies endorsements.
 *
 * <p>An endorsement that costs more is charged before the transaction, with a gateway key derived from the
 * caller's Idempotency-Key, so a declined card changes nothing and a retry can't charge twice. If the
 * optimistic lock then loses to a concurrent endorsement, the charge is refunded under its own key. Refunds owed
 * to the customer are paid only after the change commits.
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyRepository policies;
    private final ProductCatalog catalog;
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Timer readTimer;

    public PolicyService(PolicyRepository policies, ProductCatalog catalog, PaymentService payments,
                         IdempotencyService idempotency, Outbox outbox, TransactionTemplate tx, Clock clock,
                         MeterRegistry meters) {
        this.policies = policies;
        this.catalog = catalog;
        this.payments = payments;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.readTimer = Timer.builder("claimpilot.policy.read")
                .description("coverage lookup at (validAt, asOf)").publishPercentileHistogram().register(meters);
    }

    public Policy visible(Caller caller, UUID id) {
        return policies.find(id).filter(p -> caller.canSee(p.holderSubject(), p.agentSubject()))
                .orElseThrow(() -> ApiException.notFound("policy"));
    }

    public PolicyView get(Caller caller, UUID id, Instant validAt, Instant asOf) {
        Policy p = visible(caller, id);
        Instant now = clock.instant();
        Instant v = validAt == null ? now : validAt;
        Instant a = asOf == null ? now : asOf;
        PolicyVersion row = readTimer.record(() -> policies.at(id, v, a).orElse(null));
        if (row == null && validAt != null) {
            throw new ApiException(404, "no-cover", "no cover at " + v + " as recorded at " + a);
        }
        return PolicyView.of(p, row);
    }

    public List<PolicyView> mine(Caller caller) {
        return policies.forSubject(caller.subject()).stream().map(p -> PolicyView.of(p, null)).toList();
    }

    public History history(Caller caller, UUID id) {
        visible(caller, id);
        return new History(id, policies.history(id).stream().map(VersionView::of).toList());
    }

    public record EndorseCommand(Instant effectiveFrom, EndorsementChange change, String paymentToken) {
    }

    private record EndorseRequest(UUID policy, EndorseCommand command) {
    }

    public Idempotent<EndorsementResult> endorse(Caller caller, UUID id, String key, Long ifMatch,
                                                 EndorseCommand cmd) {
        Policy p = visible(caller, id);
        if (caller.is(Role.ASSESSOR)) {
            throw ApiException.forbidden("forbidden", "assessors can't change policies");
        }
        var request = new EndorseRequest(id, cmd);
        return idempotency.run("endorse", caller, key, request, EndorsementResult.class,
                () -> apply(caller, p, key, ifMatch, cmd));
    }

    private EndorsementResult apply(Caller caller, Policy p, String key, Long ifMatch, EndorseCommand cmd) {
        if (p.status() != PolicyStatus.ACTIVE) {
            throw ApiException.conflict("policy-not-active", "only an active policy can be endorsed (it is "
                    + p.status() + ")");
        }
        if (ifMatch != null && ifMatch != p.lockVersion()) {
            throw new ApiException(412, "stale-version", "policy is at version " + p.lockVersion());
        }
        Instant now = Times.micros(clock.instant());
        Instant effective = Times.micros(cmd.effectiveFrom() == null ? now : cmd.effectiveFrom());
        UUID endorsementId = UUID.randomUUID();
        EndorsementPlan plan = EndorsementPlan.plan(policies.current(p.id()), effective, now, cmd.change(),
                reprice(p), endorsementId);

        String gatewayKey = "endorse:" + p.id() + ":" + caller.subject() + ":" + key;
        String token = cmd.paymentToken() != null ? cmd.paymentToken() : p.autopayToken();
        long delta = plan.premiumDeltaMinor();
        PaymentGateway.Result charge = null;
        if (delta > 0) {
            if (token == null) {
                throw ApiException.invalid("payment-token-required", "this change costs " + delta
                        + " paise; send paymentToken");
            }
            charge = payments.chargeOrThrow(gatewayKey, token, delta);
        }
        final PaymentGateway.Result charged = charge;
        Payment payment;
        try {
            payment = tx.execute(s -> {
                if (!policies.bump(p.id(), p.lockVersion()) || !policies.close(plan.close(), plan.recordedAt())) {
                    throw ApiException.conflict("concurrent-change",
                            "the policy changed while this endorsement was being applied; re-read and retry");
                }
                policies.insertVersions(p.id(), plan.insert());
                policies.insertEndorsement(endorsementId, p.id(), effective, cmd.change(), delta, plan.recordedAt(),
                        caller.subject());
                if (cmd.change().cancel()) {
                    policies.setStatus(p.id(), p.status().moveTo(PolicyStatus.CANCELLED), null);
                }
                Payment paid = charged == null ? null
                        : payments.record(p.id(), PaymentKind.ENDORSEMENT, effective, delta, gatewayKey, charged);
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("policyId", p.id().toString());
                event.put("number", p.number());
                event.put("endorsementId", endorsementId.toString());
                event.put("effectiveFrom", effective.toString());
                event.put("premiumDeltaMinor", delta);
                event.put("cancelled", cmd.change().cancel());
                outbox.append(Outbox.POLICY_EVENTS, p.id().toString(), "PolicyEndorsed", event);
                return paid;
            });
        } catch (RuntimeException e) {
            if (charged != null) {
                compensate(p, gatewayKey, token, delta, effective, charged);
            }
            throw e;
        }
        if (delta < 0) {
            payment = refund(p, gatewayKey, token, -delta, effective);
        }
        long lock = p.lockVersion() + 1;
        return new EndorsementResult(endorsementId, p.id(), delta, payment == null ? null : payment.id(),
                payment == null ? null : payment.receiptNo(), lock,
                policies.current(p.id()).stream().map(VersionView::of).toList());
    }

    /** The change didn't commit but the card was charged: record the charge and give it back. */
    private void compensate(Policy p, String gatewayKey, String token, long amount, Instant effective,
                            PaymentGateway.Result charged) {
        try {
            String refundKey = "refund:" + gatewayKey;
            var back = payments.payout(refundKey, token, amount);
            tx.executeWithoutResult(s -> {
                payments.record(p.id(), PaymentKind.ENDORSEMENT, effective, amount, gatewayKey, charged);
                payments.record(p.id(), PaymentKind.REFUND, effective, -amount, refundKey, back);
            });
            if (!back.approved()) {
                log.error("compensating refund for {} on policy {} declined: {}", gatewayKey, p.id(),
                        back.declineReason());
            }
        } catch (RuntimeException e) {
            log.error("compensating refund for {} on policy {} failed", gatewayKey, p.id(), e);
        }
    }

    private Payment refund(Policy p, String gatewayKey, String token, long amount, Instant effective) {
        if (token == null) {
            log.warn("no payout token on policy {}; refund of {} paise left for manual handling", p.id(), amount);
            return null;
        }
        var back = payments.payout(gatewayKey, token, amount);
        return tx.execute(s -> payments.record(p.id(), PaymentKind.REFUND, effective, -amount, gatewayKey, back));
    }

    Reprice reprice(Policy p) {
        return (version, input) -> {
            var product = catalog.version(p.productId(), version);
            var premium = catalog.price(product, input);
            return new Reprice.Priced(CoverageSnapshot.of(product, input), premium.totalMinor());
        };
    }

    /** Cover in force for a claim: valid at the loss date, as we believe it now. */
    public PolicyVersion coverAt(UUID policyId, Instant lossAt) {
        return policies.at(policyId, lossAt, clock.instant())
                .orElseThrow(() -> new ApiException(422, "not-covered", "the policy had no cover on " + lossAt));
    }
}
