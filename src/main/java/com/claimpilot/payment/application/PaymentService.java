package com.claimpilot.payment.application;

import com.claimpilot.payment.domain.Payment;
import com.claimpilot.payment.domain.PaymentKind;
import com.claimpilot.shared.error.ApiException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Talks to the gateway and keeps the ledger. Gateway calls happen outside database transactions (they're slow
 * and remote); {@link #record} is called inside the business transaction that the payment pays for.
 */
@Service
public class PaymentService {

    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final Clock clock;

    public PaymentService(PaymentGateway gateway, PaymentRepository payments, Clock clock) {
        this.gateway = gateway;
        this.payments = payments;
        this.clock = clock;
    }

    public PaymentGateway.Result charge(String gatewayKey, String token, long amountMinor) {
        return gateway.charge(gatewayKey, token, amountMinor);
    }

    public PaymentGateway.Result chargeOrThrow(String gatewayKey, String token, long amountMinor) {
        var r = gateway.charge(gatewayKey, token, amountMinor);
        if (!r.approved()) {
            throw new ApiException(402, "payment-declined", "the payment was declined: " + r.declineReason());
        }
        return r;
    }

    public PaymentGateway.Result payout(String gatewayKey, String token, long amountMinor) {
        return gateway.payout(gatewayKey, token, amountMinor);
    }

    /** Records a gateway result against a policy, with a receipt number when money actually moved. */
    public Payment record(UUID policyId, PaymentKind kind, Instant periodStart, long amountMinor, String gatewayKey,
                          PaymentGateway.Result result) {
        String receipt = result.approved() ? payments.nextReceiptNumber() : null;
        return payments.insertOrGet(new Payment(UUID.randomUUID(), policyId, kind, periodStart, amountMinor,
                result.approved(), result.reference(), gatewayKey, receipt, clock.instant()));
    }

    public List<Payment> forPolicy(UUID policyId) {
        return payments.forPolicy(policyId);
    }

    public Optional<Payment> find(UUID id) {
        return payments.find(id);
    }
}
