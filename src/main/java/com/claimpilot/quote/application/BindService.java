package com.claimpilot.quote.application;

import com.claimpilot.idempotency.application.IdempotencyService;
import com.claimpilot.idempotency.application.Idempotent;
import com.claimpilot.outbox.application.Outbox;
import com.claimpilot.payment.application.PaymentService;
import com.claimpilot.payment.domain.Payment;
import com.claimpilot.payment.domain.PaymentKind;
import com.claimpilot.policy.application.PolicyDocuments;
import com.claimpilot.policy.application.PolicyRepository;
import com.claimpilot.policy.domain.CoverageSnapshot;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.policy.domain.PolicyNumbers;
import com.claimpilot.policy.domain.PolicyStatus;
import com.claimpilot.policy.domain.PolicyVersion;
import com.claimpilot.product.application.ProductCatalog;
import com.claimpilot.quote.domain.Quote;
import com.claimpilot.shared.domain.Times;
import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import com.claimpilot.shared.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Turns a quote into a policy. The first premium is charged before the transaction under the key
 * {@code bind:<quoteId>}: the gateway answers every retry or concurrent bind of the same quote with the same
 * charge, so a quote can be paid at most once however many requests race. The quote row lock then lets
 * exactly one of them issue the policy.
 */
@Service
public class BindService {

    private static final Logger log = LoggerFactory.getLogger(BindService.class);
    private static final Duration TERM = Duration.ofDays(365);
    private static final Duration MAX_FORWARD_START = Duration.ofDays(60);

    private final QuoteService quoteService;
    private final QuoteRepository quotes;
    private final PolicyRepository policies;
    private final ProductCatalog catalog;
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final Outbox outbox;
    private final PolicyDocuments documents;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BindService(QuoteService quoteService, QuoteRepository quotes, PolicyRepository policies,
                       ProductCatalog catalog, PaymentService payments, IdempotencyService idempotency, Outbox outbox,
                       PolicyDocuments documents, TransactionTemplate tx, Clock clock) {
        this.quoteService = quoteService;
        this.quotes = quotes;
        this.policies = policies;
        this.catalog = catalog;
        this.payments = payments;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.documents = documents;
        this.tx = tx;
        this.clock = clock;
    }

    public record BindRequest(String paymentToken, boolean autopay, Instant startAt) {
    }

    public record BindResult(UUID policyId, String number, PolicyStatus status, Instant termStart, Instant termEnd,
                             long annualPremiumMinor, UUID paymentId, String receiptNo) {
    }

    private record Keyed(UUID quote, BindRequest request) {
    }

    public Idempotent<BindResult> bind(Caller caller, UUID quoteId, String key, BindRequest r) {
        Quote q = quoteService.get(caller, quoteId);
        if (caller.is(Role.ASSESSOR)) {
            throw ApiException.forbidden("forbidden", "assessors can't bind quotes");
        }
        return idempotency.run("bind", caller, key, new Keyed(quoteId, r), BindResult.class, () -> issue(q, r));
    }

    private BindResult issue(Quote q, BindRequest r) {
        Instant now = Times.micros(clock.instant());
        if (q.status() == Quote.QuoteStatus.BOUND) {
            throw ApiException.conflict("quote-already-bound", "this quote is already policy " + q.policyId());
        }
        if (q.expired(now)) {
            throw new ApiException(410, "quote-expired", "the quote expired at " + q.expiresAt() + "; ask for a new one");
        }
        if (r.paymentToken() == null || r.paymentToken().isBlank()) {
            throw ApiException.invalid("payment-token-required", "send paymentToken");
        }
        Instant start = r.startAt() == null || r.startAt().isBefore(now) ? now : Times.micros(r.startAt());
        if (start.isAfter(now.plus(MAX_FORWARD_START))) {
            throw ApiException.invalid("start-too-far", "cover can start at most 60 days ahead");
        }
        String gatewayKey = "bind:" + q.id();
        long premium = q.premium().totalMinor();
        var charge = payments.chargeOrThrow(gatewayKey, r.paymentToken(), premium);

        var product = catalog.version(q.productId(), q.productVersion());
        String prefix = product.line().name().substring(0, 1);
        Policy policy = new Policy(UUID.randomUUID(), PolicyNumbers.next(prefix), q.holderSubject(), q.agentSubject(),
                q.holderName(), q.holderEmail(), q.productId(), q.productVersion(), PolicyStatus.ACTIVE, start, start,
                start.plus(TERM), null, r.autopay() ? r.paymentToken() : null, 0, now);
        PolicyVersion first = new PolicyVersion(null, start, start.plus(TERM), now, null,
                CoverageSnapshot.of(product, q.inputs()), premium, "issue", null);

        Payment paid = tx.execute(s -> {
            Quote locked = quotes.lock(q.id()).orElseThrow(() -> ApiException.notFound("quote"));
            if (locked.status() == Quote.QuoteStatus.BOUND) {
                // a concurrent bind won; it recorded the (single, shared) charge
                throw ApiException.conflict("quote-already-bound", "this quote is already policy " + locked.policyId());
            }
            policies.insert(policy);
            policies.insertVersions(policy.id(), List.of(first));
            quotes.markBound(q.id(), policy.id());
            Payment p = payments.record(policy.id(), PaymentKind.PREMIUM, start, premium, gatewayKey, charge);
            var event = new LinkedHashMap<String, Object>();
            event.put("policyId", policy.id().toString());
            event.put("number", policy.number());
            event.put("holderEmail", policy.holderEmail());
            event.put("holderName", policy.holderName());
            event.put("productId", policy.productId());
            event.put("termStart", start.toString());
            event.put("termEnd", policy.termEnd().toString());
            event.put("annualPremiumMinor", premium);
            event.put("receiptNo", p.receiptNo());
            outbox.append(Outbox.POLICY_EVENTS, policy.id().toString(), "PolicyIssued", event);
            return p;
        });
        try {
            documents.scheduleLink(policy, List.of(first));
        } catch (RuntimeException e) {
            // the schedule is regenerated on first download, so a storage hiccup must not fail a paid bind
            log.warn("schedule for {} not stored yet: {}", policy.number(), e.toString());
        }
        return new BindResult(policy.id(), policy.number(), policy.status(), policy.termStart(), policy.termEnd(),
                premium, paid.id(), paid.receiptNo());
    }
}
