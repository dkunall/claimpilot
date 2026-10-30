package com.claimpilot.claim.domain;

/** Staff who can sign off claim money. Limits per line of business live in the approval_limit table. */
public enum Authority {
    ASSESSOR,
    MANAGER
}
