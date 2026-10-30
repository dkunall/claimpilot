package com.claimpilot.payment.application;

/**
 * Port to the payment provider. Every call carries our idempotency key; the provider must return the original
 * result for a repeated key rather than move money twice, which is what real gateways (and the fake) do.
 */
public interface PaymentGateway {

    Result charge(String key, String token, long amountMinor);

    Result payout(String key, String token, long amountMinor);

    record Result(boolean approved, String reference, String declineReason) {
    }
}
