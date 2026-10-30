package com.claimpilot.policy.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Policy lifecycle. GRACE is a renewed term whose premium hasn't been collected: cover continues for the grace
 * period, then the policy lapses if nobody pays. CANCELLED, LAPSED and EXPIRED are terminal.
 */
public enum PolicyStatus {
    ACTIVE,
    GRACE,
    LAPSED,
    CANCELLED,
    EXPIRED;

    private static final Map<PolicyStatus, Set<PolicyStatus>> NEXT = Map.of(
            ACTIVE, EnumSet.of(GRACE, CANCELLED, EXPIRED),
            GRACE, EnumSet.of(ACTIVE, LAPSED, CANCELLED),
            LAPSED, EnumSet.noneOf(PolicyStatus.class),
            CANCELLED, EnumSet.noneOf(PolicyStatus.class),
            EXPIRED, EnumSet.noneOf(PolicyStatus.class));

    public boolean canMoveTo(PolicyStatus to) {
        return NEXT.get(this).contains(to);
    }

    public PolicyStatus moveTo(PolicyStatus to) {
        if (!canMoveTo(to)) {
            throw new PolicyRuleException("illegal-transition", "a " + this + " policy cannot become " + to);
        }
        return to;
    }

    public boolean inForce() {
        return this == ACTIVE || this == GRACE;
    }
}
