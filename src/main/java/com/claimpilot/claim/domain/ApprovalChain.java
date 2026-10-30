package com.claimpilot.claim.domain;

import java.util.List;
import java.util.Optional;

/**
 * Chain of responsibility over one approval attempt. Each link either decides or passes; the order is the
 * policy: duplicate and self-approval checks first, then the approver's own limit, then dual control above
 * the manager limit.
 */
public final class ApprovalChain {

    public record Signoff(String approver, Authority authority) {
    }

    public record Limits(long assessorMaxMinor, long managerMaxMinor) {
        public Limits {
            if (assessorMaxMinor <= 0 || managerMaxMinor < assessorMaxMinor) {
                throw new IllegalArgumentException("need 0 < assessor limit <= manager limit");
            }
        }
    }

    public record Request(long amountMinor, Signoff signoff, String assessedBy, List<Signoff> earlier, Limits limits) {
        public Request {
            earlier = List.copyOf(earlier);
        }
    }

    @FunctionalInterface
    public interface Link {
        Optional<ApprovalDecision> handle(Request r);
    }

    private final List<Link> links;

    public ApprovalChain(List<Link> links) {
        this.links = List.copyOf(links);
    }

    public static ApprovalChain standard() {
        return new ApprovalChain(List.of(
                ApprovalChain::notTwice,
                ApprovalChain::fourEyesAboveAssessorLimit,
                ApprovalChain::assessorLimit,
                ApprovalChain::managerLimit,
                ApprovalChain::dualControl));
    }

    public ApprovalDecision decide(Request r) {
        for (Link link : links) {
            Optional<ApprovalDecision> d = link.handle(r);
            if (d.isPresent()) {
                return d.get();
            }
        }
        return new ApprovalDecision.Denied("no-authority", "nobody in the chain could approve " + r.amountMinor());
    }

    static Optional<ApprovalDecision> notTwice(Request r) {
        boolean again = r.earlier().stream().anyMatch(s -> s.approver().equals(r.signoff().approver()));
        return again ? Optional.of(new ApprovalDecision.Denied("already-signed", "you already signed this claim off"))
                : Optional.empty();
    }

    /** Within the assessor limit the assessor may approve their own figure; above it, a second person must. */
    static Optional<ApprovalDecision> fourEyesAboveAssessorLimit(Request r) {
        if (r.amountMinor() > r.limits().assessorMaxMinor() && r.signoff().approver().equals(r.assessedBy())) {
            return Optional.of(new ApprovalDecision.Denied("four-eyes",
                    "above " + r.limits().assessorMaxMinor() + " the approver can't be the assessor"));
        }
        return Optional.empty();
    }

    static Optional<ApprovalDecision> assessorLimit(Request r) {
        if (r.signoff().authority() != Authority.ASSESSOR) {
            return Optional.empty();
        }
        if (r.amountMinor() <= r.limits().assessorMaxMinor()) {
            return Optional.of(new ApprovalDecision.Approved("within the assessor limit"));
        }
        return Optional.of(new ApprovalDecision.Escalated(Authority.MANAGER,
                "over the assessor limit " + r.limits().assessorMaxMinor() + "; a manager must approve"));
    }

    static Optional<ApprovalDecision> managerLimit(Request r) {
        if (r.signoff().authority() == Authority.MANAGER && r.amountMinor() <= r.limits().managerMaxMinor()) {
            return Optional.of(new ApprovalDecision.Approved("within the manager limit"));
        }
        return Optional.empty();
    }

    /** Above the manager limit two different managers must sign. */
    static Optional<ApprovalDecision> dualControl(Request r) {
        if (r.signoff().authority() != Authority.MANAGER) {
            return Optional.empty();
        }
        boolean otherManager = r.earlier().stream().anyMatch(s -> s.authority() == Authority.MANAGER
                && !s.approver().equals(r.signoff().approver()));
        return Optional.of(otherManager
                ? new ApprovalDecision.Approved("second manager signature over " + r.limits().managerMaxMinor())
                : new ApprovalDecision.Escalated(Authority.MANAGER,
                        "over the manager limit " + r.limits().managerMaxMinor() + "; a second manager must approve"));
    }
}
