package com.claimpilot.renewal.application;

import com.claimpilot.idempotency.application.IdempotencyService;
import com.claimpilot.idempotency.application.Idempotent;
import com.claimpilot.outbox.application.Outbox;
import com.claimpilot.payment.application.PaymentGateway;
import com.claimpilot.payment.application.PaymentService;
import com.claimpilot.payment.domain.Payment;
import com.claimpilot.payment.domain.PaymentKind;
import com.claimpilot.policy.application.PolicyRepository;
import com.claimpilot.policy.application.PolicyService;
import com.claimpilot.policy.domain.CoverageSnapshot;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.policy.domain.PolicyStatus;
import com.claimpilot.policy.domain.PolicyVersion;
import com.claimpilot.product.application.ProductCatalog;
import com.claimpilot.product.domain.RatingException;
import com.claimpilot.renewal.application.RenewalRepository.Renewal;
import com.claimpilot.renewal.application.RenewalRepository.Status;
import com.claimpilot.shared.config.ClaimPilotProperties;
import com.claimpilot.shared.domain.Times;
import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renews terms, collects premiums for policies in grace, lapses the ones nobody paid for and queues reminders.
 *
 * <p>Every renewal item is idempotent on (policy, period start): the gateway key is derived from it, the policy
 * row is locked and re-checked before anything is written, and the renewal row's primary key rejects a second
 * write. So a batch partition that is retried after a crash, or two overlapping runs, renew and charge once.
 * The charge happens before the transaction (gateway calls never hold a row lock); if the transaction then finds
 * the term already renewed, the gateway's per-key memo means that charge was the same single charge.
 */
@Service
public class RenewalService {

    /** Business days are Indian days: "run for 31 Oct" means terms ending before 1 Nov 00:00 IST. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    public static final Duration TERM = Duration.ofDays(365);

    private static final Logger log = LoggerFactory.getLogger(RenewalService.class);

    public enum Outcome { RENEWED, GRACE, EXPIRED, SKIPPED }

    private final PolicyRepository policies;
    private final PolicyService policyService;
    private final RenewalRepository renewals;
    private final ProductCatalog catalog;
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Duration grace;
    private final Duration reminderWindow;

    public RenewalService(PolicyRepository policies, PolicyService policyService, RenewalRepository renewals,
                          ProductCatalog catalog, PaymentService payments, IdempotencyService idempotency,
                          Outbox outbox, TransactionTemplate tx, Clock clock, MeterRegistry meters,
                          ClaimPilotProperties props) {
        this.policies = policies;
        this.policyService = policyService;
        this.renewals = renewals;
        this.catalog = catalog;
        this.payments = payments;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        this.grace = Duration.ofDays(props.renewals().graceDays());
        this.reminderWindow = Duration.ofDays(props.renewals().reminderDaysBefore());
    }

    /** Terms ending before this instant are due in the run for {@code date}. */
    public static Instant cutoff(LocalDate date) {
        return date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
    }

    public static String gatewayKey(UUID policyId, Instant periodStart) {
        return "renewal:" + policyId + ":" + periodStart;
    }

    public Outcome renew(UUID policyId, Instant cutoff, Long jobExecutionId) {
        Policy p = policies.find(policyId).orElse(null);
        // a term that ended more than a year before the cutoff is not renewed (and a renewed one is never due
        // again in the same run, which makes a restarted partition safe to re-read)
        if (p == null || p.status() != PolicyStatus.ACTIVE || p.termEnd().isAfter(cutoff)
                || !p.termEnd().isAfter(cutoff.minus(TERM))) {
            return count(Outcome.SKIPPED);
        }
        Instant periodStart = p.termEnd();
        Instant now = Times.micros(clock.instant());
        PolicyVersion last = policies.current(policyId).stream()
                .filter(v -> v.validTo().equals(periodStart)).findFirst().orElse(null);
        if (last == null) {
            log.error("policy {} has no current version ending at its term end {}", p.number(), periodStart);
            return count(Outcome.SKIPPED);
        }
        var product = catalog.latest(p.productId());
        CoverageSnapshot snapshot;
        long premium;
        try {
            var inputs = last.coverage().inputs();
            snapshot = CoverageSnapshot.of(product, inputs);
            premium = catalog.price(product, inputs).totalMinor();
        } catch (RatingException e) {
            return expire(p, periodStart, "the current product no longer rates this cover: " + e.getMessage());
        }
        String key = gatewayKey(policyId, periodStart);
        PaymentGateway.Result charge = p.autopayToken() == null ? null
                : payments.charge(key, p.autopayToken(), premium);
        boolean paid = charge != null && charge.approved();
        Instant termEnd = periodStart.plus(TERM);
        Instant graceUntil = paid ? null : periodStart.plus(grace);

        Outcome outcome = tx.execute(s -> {
            Policy locked = policies.lock(policyId).orElseThrow();
            if (locked.status() != PolicyStatus.ACTIVE || !locked.termEnd().equals(periodStart)) {
                return Outcome.SKIPPED; // another run renewed (or someone cancelled) it after we read it
            }
            Payment payment = charge == null ? null
                    : payments.record(policyId, PaymentKind.RENEWAL, periodStart, premium, key, charge);
            boolean fresh = renewals.insert(new Renewal(policyId, periodStart,
                    paid ? Status.RENEWED : Status.PAYMENT_FAILED, product.version(), premium,
                    paid ? payment.id() : null, jobExecutionId, now));
            if (!fresh) {
                return Outcome.SKIPPED;
            }
            policies.insertVersions(policyId, List.of(new PolicyVersion(null, periodStart, termEnd, now, null,
                    snapshot, premium, "renewal", null)));
            PolicyStatus next = paid ? PolicyStatus.ACTIVE : locked.status().moveTo(PolicyStatus.GRACE);
            policies.startTerm(policyId, product.version(), periodStart, termEnd, next, graceUntil);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("policyId", policyId.toString());
            event.put("number", p.number());
            event.put("periodStart", periodStart.toString());
            event.put("termEnd", termEnd.toString());
            event.put("premiumMinor", premium);
            event.put("productVersion", product.version());
            if (paid) {
                event.put("receiptNo", payment.receiptNo());
            } else {
                event.put("graceUntil", graceUntil.toString());
                event.put("reason", charge == null ? "no autopay mandate" : charge.declineReason());
            }
            outbox.append(Outbox.POLICY_EVENTS, policyId.toString(), paid ? "PolicyRenewed" : "RenewalPaymentDue",
                    event);
            return paid ? Outcome.RENEWED : Outcome.GRACE;
        });
        return count(outcome);
    }

    private Outcome expire(Policy p, Instant periodStart, String why) {
        Outcome o = tx.execute(s -> {
            Policy locked = policies.lock(p.id()).orElseThrow();
            if (locked.status() != PolicyStatus.ACTIVE || !locked.termEnd().equals(periodStart)) {
                return Outcome.SKIPPED;
            }
            policies.setStatus(p.id(), locked.status().moveTo(PolicyStatus.EXPIRED), null);
            outbox.append(Outbox.POLICY_EVENTS, p.id().toString(), "PolicyExpired",
                    Map.of("policyId", p.id().toString(), "number", p.number(), "reason", why));
            return Outcome.EXPIRED;
        });
        return count(o);
    }

    /** Ends cover at the end of grace for policies nobody paid for. Returns how many lapsed. */
    public int lapse(Instant cutoff) {
        int n = 0;
        for (UUID id : renewals.graceExpired(cutoff)) {
            Boolean lapsed = tx.execute(s -> {
                Policy p = policies.lock(id).orElseThrow();
                if (p.status() != PolicyStatus.GRACE || p.graceUntil() == null || p.graceUntil().isAfter(cutoff)) {
                    return false;
                }
                Instant now = Times.micros(clock.instant());
                Instant end = p.graceUntil();
                List<Long> close = new ArrayList<>();
                List<PolicyVersion> keep = new ArrayList<>();
                for (PolicyVersion v : policies.current(id)) {
                    if (v.validTo().isAfter(end)) {
                        close.add(v.id());
                        if (v.validFrom().isBefore(end)) {
                            keep.add(v.segment(v.validFrom(), end, now, v.coverage(), v.annualPremiumMinor(),
                                    "lapsed", v.endorsementId()));
                        }
                    }
                }
                if (!policies.bump(id, p.lockVersion()) || !policies.close(close, now)) {
                    throw new IllegalStateException("policy " + id + " changed under its row lock");
                }
                policies.insertVersions(id, keep);
                policies.setStatus(id, p.status().moveTo(PolicyStatus.LAPSED), end);
                outbox.append(Outbox.POLICY_EVENTS, id.toString(), "PolicyLapsed",
                        Map.of("policyId", id.toString(), "number", p.number(), "coverEndedAt", end.toString()));
                return true;
            });
            if (Boolean.TRUE.equals(lapsed)) {
                n++;
                meters.counter("claimpilot.renewals", "outcome", "lapsed").increment();
            }
        }
        return n;
    }

    /** Queues one reminder per policy term that ends within the reminder window. Returns how many. */
    public int remind(Instant cutoff) {
        int n = 0;
        Instant now = clock.instant();
        for (var due : renewals.remindersDue(cutoff, cutoff.plus(reminderWindow))) {
            Boolean sent = tx.execute(s -> {
                if (!renewals.insertReminder(due.policyId(), due.termEnd(), now)) {
                    return false;
                }
                outbox.append(Outbox.POLICY_EVENTS, due.policyId().toString(), "RenewalReminder",
                        Map.of("policyId", due.policyId().toString(), "number", due.number(),
                                "termEnd", due.termEnd().toString(), "autopay", due.autopay()));
                return true;
            });
            if (Boolean.TRUE.equals(sent)) {
                n++;
            }
        }
        return n;
    }

    public record PayCommand(String paymentToken) {
    }

    public record PaymentResult(UUID policyId, PolicyStatus status, Instant termStart, Instant termEnd,
                                long amountMinor, UUID paymentId, String receiptNo) {
    }

    private record Keyed(UUID policy, PayCommand command) {
    }

    /** Pays the premium of a policy in grace; it goes back to ACTIVE with no gap in cover. */
    public Idempotent<PaymentResult> pay(Caller caller, UUID policyId, String key, PayCommand cmd) {
        policyService.visible(caller, policyId);
        if (caller.role().worksClaims()) {
            throw ApiException.forbidden("forbidden", "claims staff can't take premium payments");
        }
        if (cmd == null || cmd.paymentToken() == null || cmd.paymentToken().isBlank()) {
            throw ApiException.invalid("payment-token-required", "send paymentToken");
        }
        return idempotency.run("premium", caller, key, new Keyed(policyId, cmd), PaymentResult.class,
                () -> collect(policyId, cmd.paymentToken()));
    }

    private PaymentResult collect(UUID policyId, String token) {
        Policy p = policies.find(policyId).orElseThrow(() -> ApiException.notFound("policy"));
        if (p.status() != PolicyStatus.GRACE) {
            throw ApiException.conflict("nothing-due", "the policy is " + p.status() + "; no premium is due");
        }
        Instant periodStart = p.termStart();
        Renewal due = renewals.find(policyId, periodStart)
                .filter(r -> r.status() == Status.PAYMENT_FAILED)
                .orElseThrow(() -> ApiException.conflict("nothing-due", "no unpaid renewal for this term"));
        // one key per unpaid term, whoever pays: two payers racing get the gateway's single answer
        String key = "grace:" + policyId + ":" + periodStart;
        var charge = payments.chargeOrThrow(key, token, due.premiumMinor());
        Payment payment = tx.execute(s -> {
            Policy locked = policies.lock(policyId).orElseThrow();
            Payment paid = payments.record(policyId, PaymentKind.RENEWAL, periodStart, due.premiumMinor(), key,
                    charge);
            if (locked.status() == PolicyStatus.GRACE && locked.termStart().equals(periodStart)) {
                renewals.markPaid(policyId, periodStart, paid.id());
                policies.setStatus(policyId, locked.status().moveTo(PolicyStatus.ACTIVE), null);
                outbox.append(Outbox.POLICY_EVENTS, policyId.toString(), "PolicyRenewed",
                        Map.of("policyId", policyId.toString(), "number", p.number(),
                                "periodStart", periodStart.toString(), "termEnd", locked.termEnd().toString(),
                                "premiumMinor", due.premiumMinor(), "receiptNo", paid.receiptNo()));
                return paid;
            }
            if (locked.status() == PolicyStatus.ACTIVE && locked.termStart().equals(periodStart)) {
                return paid; // a concurrent payer with the same key got there first; same single charge
            }
            return null;
        });
        if (payment == null) {
            // lapsed or cancelled between our read and the charge: give the money back
            var back = payments.payout("refund:" + key, token, due.premiumMinor());
            tx.executeWithoutResult(s -> payments.record(policyId, PaymentKind.REFUND, periodStart,
                    -due.premiumMinor(), "refund:" + key, back));
            throw ApiException.conflict("nothing-due", "the policy is no longer in grace; the payment was refunded");
        }
        Policy after = policies.find(policyId).orElseThrow();
        return new PaymentResult(policyId, after.status(), after.termStart(), after.termEnd(), due.premiumMinor(),
                payment.id(), payment.receiptNo());
    }

    public List<Renewal> history(Caller caller, UUID policyId) {
        policyService.visible(caller, policyId);
        return renewals.forPolicy(policyId);
    }

    private Outcome count(Outcome o) {
        meters.counter("claimpilot.renewals", "outcome", o.name().toLowerCase()).increment();
        return o;
    }
}
