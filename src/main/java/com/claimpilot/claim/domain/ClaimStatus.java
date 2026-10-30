package com.claimpilot.claim.domain;

import com.claimpilot.shared.domain.DomainConflictException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The claim workflow. Every move goes through {@link #moveTo}, so a claim can't skip triage or be paid twice
 * by a code path that forgot to check.
 *
 * <pre>
 * FNOL -> TRIAGE -> STP_APPROVED ----------------------------> SETTLED
 *                -> ASSESSMENT -> APPROVED -----------------> SETTLED
 *                              -> INVESTIGATION -> APPROVED -> SETTLED
 *                              -> REJECTED       -> REJECTED
 * </pre>
 */
public enum ClaimStatus {
    FNOL,
    TRIAGE,
    STP_APPROVED,
    ASSESSMENT,
    INVESTIGATION,
    APPROVED,
    REJECTED,
    SETTLED;

    private static final Map<ClaimStatus, Set<ClaimStatus>> NEXT = new EnumMap<>(ClaimStatus.class);

    static {
        NEXT.put(FNOL, EnumSet.of(TRIAGE));
        NEXT.put(TRIAGE, EnumSet.of(STP_APPROVED, ASSESSMENT));
        NEXT.put(ASSESSMENT, EnumSet.of(INVESTIGATION, APPROVED, REJECTED));
        NEXT.put(INVESTIGATION, EnumSet.of(APPROVED, REJECTED));
        NEXT.put(STP_APPROVED, EnumSet.of(SETTLED));
        NEXT.put(APPROVED, EnumSet.of(SETTLED));
        NEXT.put(REJECTED, EnumSet.noneOf(ClaimStatus.class));
        NEXT.put(SETTLED, EnumSet.noneOf(ClaimStatus.class));
    }

    public boolean canMoveTo(ClaimStatus next) {
        return NEXT.get(this).contains(next);
    }

    public ClaimStatus moveTo(ClaimStatus next) {
        if (!canMoveTo(next)) {
            throw new DomainConflictException("illegal-transition", "a " + this + " claim can't become " + next);
        }
        return next;
    }

    /** Waiting for a person to decide. */
    public boolean open() {
        return this == ASSESSMENT || this == INVESTIGATION;
    }

    public boolean payable() {
        return this == APPROVED || this == STP_APPROVED;
    }

    public boolean terminal() {
        return NEXT.get(this).isEmpty();
    }
}
