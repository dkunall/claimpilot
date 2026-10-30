package com.claimpilot.claim.application;

import com.claimpilot.claim.domain.ClaimStatus;
import com.claimpilot.claim.domain.Settlement;
import com.claimpilot.claim.domain.Transition;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the claims API returns. Fraud fields are filled only for assessors and managers. */
public final class ClaimViews {

    private ClaimViews() {
    }

    public record Party(String type, String value) {
    }

    public record DocumentSpec(String fileName, String contentType, long sizeBytes) {
    }

    public record FnolCommand(UUID policyId, String coverageCode, Instant lossAt, long claimedMinor,
                              String description, List<Party> parties, String payoutToken,
                              List<DocumentSpec> documents) {
    }

    public record Upload(UUID documentId, String fileName, URI url, Map<String, List<String>> headers,
                         Instant expiresAt) {
    }

    public record FnolResult(ClaimView claim, List<Upload> uploads) {
    }

    public record DocumentView(UUID id, String fileName, String contentType, long sizeBytes, boolean uploaded) {
    }

    public record Approval(String approver, String authority) {
    }

    public record ClaimView(UUID id, String number, UUID policyId, String coverageCode, Instant lossAt,
                            Instant reportedAt, long claimedMinor, long reserveMinor, Long assessedMinor,
                            Long approvedMinor, ClaimStatus status, Boolean straightThrough,
                            List<String> triageReasons, Double fraudScore, List<String> fraudReasons,
                            String assessedBy, int documentsRequired, Instant decidedAt, Instant settledAt,
                            long version, List<DocumentView> documents, List<Transition> history,
                            List<Approval> approvals, Settlement settlement) {
    }

    public enum Outcome { APPROVE, INVESTIGATE, REJECT }

    public record AssessCommand(long assessedMinor, Outcome outcome, String note) {
    }

    public record ApproveResult(String decision, String pendingAuthority, String reason, ClaimView claim) {
    }
}
