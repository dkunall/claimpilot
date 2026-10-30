package com.claimpilot.policy.application;

import com.claimpilot.policy.domain.CoverageSnapshot;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.policy.domain.PolicyStatus;
import com.claimpilot.policy.domain.PolicyVersion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PolicyViews {

    private PolicyViews() {
    }

    public record PolicyView(UUID id, String number, PolicyStatus status, String productId, String holderSubject,
                             String holderName, String holderEmail, Instant termStart, Instant termEnd,
                             Instant graceUntil, long lockVersion, VersionView cover) {

        public static PolicyView of(Policy p, PolicyVersion v) {
            return new PolicyView(p.id(), p.number(), p.status(), p.productId(), p.holderSubject(), p.holderName(),
                    p.holderEmail(), p.termStart(), p.termEnd(), p.graceUntil(), p.lockVersion(),
                    v == null ? null : VersionView.of(v));
        }
    }

    public record VersionView(Instant validFrom, Instant validTo, Instant recordedFrom, Instant recordedTo,
                              CoverageSnapshot coverage, long annualPremiumMinor, String reason,
                              UUID endorsementId) {

        public static VersionView of(PolicyVersion v) {
            return new VersionView(v.validFrom(), v.validTo(), v.recordedFrom(), v.recordedTo(), v.coverage(),
                    v.annualPremiumMinor(), v.reason(), v.endorsementId());
        }
    }

    public record History(UUID policyId, List<VersionView> versions) {
    }

    public record EndorsementResult(UUID endorsementId, UUID policyId, long premiumDeltaMinor, UUID paymentId,
                                    String receiptNo, long lockVersion, List<VersionView> current) {
    }
}
