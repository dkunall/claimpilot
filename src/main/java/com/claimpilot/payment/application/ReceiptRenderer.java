package com.claimpilot.payment.application;

import com.claimpilot.payment.domain.Payment;

public interface ReceiptRenderer {

    byte[] receipt(Payment payment, String policyNumber, String holderName);
}
