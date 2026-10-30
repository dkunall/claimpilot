package com.claimpilot.payment.application;

import com.claimpilot.payment.domain.Payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    /** Inserts, or returns the row already stored for the same gateway key. */
    Payment insertOrGet(Payment p);

    Optional<Payment> find(UUID id);

    List<Payment> forPolicy(UUID policyId);

    String nextReceiptNumber();
}
