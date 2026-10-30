package com.claimpilot.shared.security;

/** Anyone with a valid token is a customer unless the staff table says otherwise. */
public enum Role {
    CUSTOMER,
    AGENT,
    ASSESSOR,
    MANAGER;

    public boolean isStaff() {
        return this != CUSTOMER;
    }

    /** Assessors and managers work claims; agents sell policies. */
    public boolean worksClaims() {
        return this == ASSESSOR || this == MANAGER;
    }
}
