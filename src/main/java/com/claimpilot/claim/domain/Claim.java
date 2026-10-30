package com.claimpilot.claim.domain;

import com.claimpilot.fraud.domain.FraudScore;
import com.claimpilot.shared.domain.DomainConflictException;
import com.claimpilot.shared.domain.DomainRuleException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A claim against one coverage of one policy version. Every state change returns a new value via
 * {@link ClaimStatus#moveTo}, and money fields only move in the direction the workflow allows.
 */
public record Claim(
        UUID id,
        String number,
        UUID policyId,
        String coverageCode,
        Long coverageVersionId,
        Instant lossAt,
        Instant reportedAt,
        long claimedMinor,
        long reserveMinor,
        Long assessedMinor,
        Long approvedMinor,
        ClaimStatus status,
        Boolean stpEligible,
        List<String> triageReasons,
        Double fraudScore,
        List<String> fraudReasons,
        String assessedBy,
        String payoutToken,
        String description,
        int documentsRequired,
        String createdBy,
        Instant decidedAt,
        Instant settledAt,
        long lockVersion) {

    public Claim {
        triageReasons = triageReasons == null ? List.of() : List.copyOf(triageReasons);
        fraudReasons = fraudReasons == null ? List.of() : List.copyOf(fraudReasons);
        if (claimedMinor <= 0) {
            throw new DomainRuleException("invalid-amount", "claimed amount must be positive");
        }
    }

    public Claim startTriage() {
        return with(status.moveTo(ClaimStatus.TRIAGE));
    }

    public Claim triaged(TriageResult t, FraudScore f, Instant at) {
        ClaimStatus next = status.moveTo(t.stp() ? ClaimStatus.STP_APPROVED : ClaimStatus.ASSESSMENT);
        return new Claim(id, number, policyId, coverageCode, coverageVersionId, lossAt, reportedAt, claimedMinor,
                t.reserveMinor(), assessedMinor, t.stp() ? t.reserveMinor() : null, next, t.stp(), t.reasons(),
                f.score(), f.reasons(), assessedBy, payoutToken, description, documentsRequired, createdBy,
                t.stp() ? at : null, settledAt, lockVersion);
    }

    /** An assessor's figure. It can't exceed what triage reserved, which is already capped at the cover limit. */
    public Claim assessed(long amountMinor, String assessor) {
        if (!status.open()) {
            throw new DomainConflictException("not-open",
                    "only a claim in assessment or investigation can be assessed; this one is " + status);
        }
        if (amountMinor < 0 || amountMinor > reserveMinor) {
            throw new DomainRuleException("assessed-out-of-range",
                    "assessed amount must be between 0 and the payable reserve " + reserveMinor);
        }
        return new Claim(id, number, policyId, coverageCode, coverageVersionId, lossAt, reportedAt, claimedMinor,
                reserveMinor, amountMinor, approvedMinor, status, stpEligible, triageReasons, fraudScore,
                fraudReasons, assessor, payoutToken, description, documentsRequired, createdBy, decidedAt, settledAt,
                lockVersion);
    }

    public Claim investigate() {
        return with(status.moveTo(ClaimStatus.INVESTIGATION));
    }

    public Claim approved(Instant at) {
        if (assessedMinor == null || assessedMinor == 0) {
            throw new DomainRuleException("not-assessed", "a claim needs a positive assessed amount before approval");
        }
        return new Claim(id, number, policyId, coverageCode, coverageVersionId, lossAt, reportedAt, claimedMinor,
                reserveMinor, assessedMinor, assessedMinor, status.moveTo(ClaimStatus.APPROVED), stpEligible,
                triageReasons, fraudScore, fraudReasons, assessedBy, payoutToken, description, documentsRequired,
                createdBy, at, settledAt, lockVersion);
    }

    public Claim rejected(Instant at) {
        return new Claim(id, number, policyId, coverageCode, coverageVersionId, lossAt, reportedAt, claimedMinor,
                reserveMinor, assessedMinor, null, status.moveTo(ClaimStatus.REJECTED), stpEligible, triageReasons,
                fraudScore, fraudReasons, assessedBy, payoutToken, description, documentsRequired, createdBy, at,
                settledAt, lockVersion);
    }

    public Claim settled(Instant at) {
        return new Claim(id, number, policyId, coverageCode, coverageVersionId, lossAt, reportedAt, claimedMinor,
                reserveMinor, assessedMinor, approvedMinor, status.moveTo(ClaimStatus.SETTLED), stpEligible,
                triageReasons, fraudScore, fraudReasons, assessedBy, payoutToken, description, documentsRequired,
                createdBy, decidedAt, at, lockVersion);
    }

    /** A new payout destination, for retrying a declined settlement. */
    public Claim withPayoutToken(String token) {
        if (!status.payable()) {
            throw new DomainConflictException("not-payable", "only an approved, unsettled claim can change payout details");
        }
        return new Claim(id, number, policyId, coverageCode, coverageVersionId, lossAt, reportedAt, claimedMinor,
                reserveMinor, assessedMinor, approvedMinor, status, stpEligible, triageReasons, fraudScore,
                fraudReasons, assessedBy, token, description, documentsRequired, createdBy, decidedAt, settledAt,
                lockVersion);
    }

    private Claim with(ClaimStatus s) {
        return new Claim(id, number, policyId, coverageCode, coverageVersionId, lossAt, reportedAt, claimedMinor,
                reserveMinor, assessedMinor, approvedMinor, s, stpEligible, triageReasons, fraudScore, fraudReasons,
                assessedBy, payoutToken, description, documentsRequired, createdBy, decidedAt, settledAt, lockVersion);
    }
}
