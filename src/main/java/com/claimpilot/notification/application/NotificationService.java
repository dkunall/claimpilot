package com.claimpilot.notification.application;

import com.claimpilot.claim.application.ClaimRepository;
import com.claimpilot.claim.domain.Claim;
import com.claimpilot.outbox.application.EventEnvelope;
import com.claimpilot.policy.application.PolicyRepository;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.shared.mail.Mailer;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns policy and claim events into customer emails. Events carry ids, not contact details; the address is
 * looked up when the mail is sent, so a changed address is honoured and Kafka holds no contact details.
 *
 * <p>The dedup row and the send share a transaction: if the SMTP call fails the row rolls back and Kafka
 * redelivers. If the process dies after the send but before the commit, the mail goes out twice; that's the
 * at-least-once trade-off, and it's the only path that duplicates.
 */
@Service
public class NotificationService {

    static final String CONSUMER = "notifications";
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu")
            .withZone(java.time.ZoneId.of("Asia/Kolkata"));

    private final ProcessedEvents processed;
    private final PolicyRepository policies;
    private final ClaimRepository claims;
    private final Mailer mailer;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public NotificationService(ProcessedEvents processed, PolicyRepository policies, ClaimRepository claims,
                               Mailer mailer, TransactionTemplate tx, MeterRegistry meters) {
        this.processed = processed;
        this.policies = policies;
        this.claims = claims;
        this.mailer = mailer;
        this.tx = tx;
        this.meters = meters;
    }

    record Message(String to, String subject, String body) {
    }

    /** Returns true when a mail was sent for this event (false for duplicates and events nobody is told about). */
    public boolean handle(EventEnvelope event) {
        Boolean sent = tx.execute(s -> {
            if (!processed.markProcessed(CONSUMER, event.eventId())) {
                meters.counter("claimpilot.notifications", "result", "duplicate").increment();
                return false;
            }
            Optional<Message> m = compose(event);
            if (m.isEmpty()) {
                return false;
            }
            mailer.send(List.of(m.get().to()), m.get().subject(), m.get().body(), null);
            meters.counter("claimpilot.notifications", "result", "sent", "type", event.type()).increment();
            return true;
        });
        return Boolean.TRUE.equals(sent);
    }

    Optional<Message> compose(EventEnvelope e) {
        Map<String, Object> d = e.data();
        return switch (e.type()) {
            case "PolicyIssued" -> policy(d).map(p -> mail(p, "Your policy " + p.number() + " is active",
                    "Cover runs from " + day(d.get("termStart")) + " to " + day(d.get("termEnd"))
                            + ". Premium paid: " + money(d.get("annualPremiumMinor")) + " (receipt "
                            + d.get("receiptNo") + ")."));
            case "PolicyEndorsed" -> policy(d).map(p -> mail(p, "Policy " + p.number() + " was changed",
                    "A change effective " + day(d.get("effectiveFrom")) + " was applied. Premium difference: "
                            + money(d.get("premiumDeltaMinor")) + "."));
            case "PolicyRenewed" -> policy(d).map(p -> mail(p, "Policy " + p.number() + " renewed",
                    "Your cover continues until " + day(d.get("termEnd")) + ". Premium: "
                            + money(d.get("premiumMinor")) + " (receipt " + d.get("receiptNo") + ")."));
            case "RenewalPaymentDue" -> policy(d).map(p -> mail(p, "Payment due for policy " + p.number(),
                    "We couldn't collect the renewal premium of " + money(d.get("premiumMinor"))
                            + ". Your cover continues until " + day(d.get("graceUntil"))
                            + "; pay before then to keep it without a break."));
            case "PolicyLapsed" -> policy(d).map(p -> mail(p, "Policy " + p.number() + " has lapsed",
                    "The renewal premium wasn't paid, so cover ended on " + day(d.get("coverEndedAt")) + "."));
            case "RenewalReminder" -> policy(d).map(p -> mail(p, "Policy " + p.number() + " renews soon",
                    "Your current term ends on " + day(d.get("termEnd")) + ". "
                            + (Boolean.TRUE.equals(d.get("autopay"))
                            ? "We'll collect the premium from your saved payment method."
                            : "You'll get a payment link when it's due.")));
            case "ClaimReported" -> claim(d).map(c -> mail(c, "We received claim " + c.claim().number(),
                    "Claim " + c.claim().number() + " is registered. Upload the requested documents to continue."));
            case "ClaimSettled" -> claim(d).map(c -> mail(c, "Claim " + c.claim().number() + " paid",
                    "We paid " + money(d.get("amountMinor")) + " on claim " + c.claim().number() + "."));
            case "ClaimRejected" -> claim(d).map(c -> mail(c, "Update on claim " + c.claim().number(),
                    "We couldn't approve claim " + c.claim().number() + ". Reply to this mail to ask for a review."));
            default -> Optional.empty();
        };
    }

    private record ClaimAndPolicy(Claim claim, Policy policy) {
    }

    private Optional<Policy> policy(Map<String, Object> d) {
        Object id = d.get("policyId");
        Optional<Policy> p = id == null ? Optional.empty() : policies.find(UUID.fromString(id.toString()));
        if (p.isEmpty()) {
            log.warn("notification for unknown policy {}", id);
        }
        return p;
    }

    private Optional<ClaimAndPolicy> claim(Map<String, Object> d) {
        Object id = d.get("claimId");
        return Optional.ofNullable(id).flatMap(i -> claims.find(UUID.fromString(i.toString())))
                .flatMap(c -> policies.find(c.policyId()).map(p -> new ClaimAndPolicy(c, p)));
    }

    private static Message mail(Policy p, String subject, String body) {
        return new Message(p.holderEmail(), subject, "Dear " + p.holderName() + ",\n\n" + body + "\n\nClaimPilot");
    }

    private static Message mail(ClaimAndPolicy c, String subject, String body) {
        return mail(c.policy(), subject, body);
    }

    static String money(Object minor) {
        if (minor == null) {
            return "-";
        }
        BigDecimal rupees = new BigDecimal(minor.toString()).movePointLeft(2);
        NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return "INR " + f.format(rupees);
    }

    static String day(Object instant) {
        return instant == null ? "-" : DAY.format(Instant.parse(instant.toString()));
    }
}
