package com.claimpilot.fraud.domain;

/**
 * Identifiers attached to a claim. Phone, bank account and device belong to a person, so two holders sharing
 * one is a strong link. Garages and hospitals serve thousands of honest customers; sharing one is only a
 * weak, weighted signal and never merges people.
 */
public enum PartyType {
    PHONE(true),
    BANK_ACCOUNT(true),
    DEVICE(true),
    GARAGE(false),
    HOSPITAL(false);

    private final boolean personal;

    PartyType(boolean personal) {
        this.personal = personal;
    }

    public boolean personal() {
        return personal;
    }
}
