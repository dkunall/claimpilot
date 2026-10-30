package com.claimpilot.claim.domain;

/** What the approval chain says about one person's sign-off. */
public sealed interface ApprovalDecision {

    /** The claim is approved with this sign-off. */
    record Approved(String why) implements ApprovalDecision {
    }

    /** The sign-off is recorded but someone with {@code needed} authority must also approve. */
    record Escalated(Authority needed, String why) implements ApprovalDecision {
    }

    /** The sign-off is refused and not recorded. */
    record Denied(String code, String why) implements ApprovalDecision {
    }
}
