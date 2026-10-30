package com.claimpilot.renewal.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenewalRepository {

    enum Status { RENEWED, PAYMENT_FAILED }

    record Renewal(UUID policyId, Instant periodStart, Status status, int productVersion, long premiumMinor,
                   UUID paymentId, Long jobExecutionId, Instant createdAt) {
    }

    record ReminderDue(UUID policyId, String number, Instant termEnd, boolean autopay) {
    }

    /** False when the period was already renewed: the batch item is a replay. */
    boolean insert(Renewal r);

    Optional<Renewal> find(UUID policyId, Instant periodStart);

    void markPaid(UUID policyId, Instant periodStart, UUID paymentId);

    List<Renewal> forPolicy(UUID policyId);

    /** Renewal outcomes written by one job execution, by status. */
    java.util.Map<Status, Long> countByExecution(long jobExecutionId);

    List<UUID> graceExpired(Instant runAt);

    List<ReminderDue> remindersDue(Instant from, Instant to);

    boolean insertReminder(UUID policyId, Instant termEnd, Instant at);
}
