package com.claimpilot.claim.application;

import com.claimpilot.claim.domain.ApprovalChain;
import com.claimpilot.claim.domain.Claim;
import com.claimpilot.claim.domain.ClaimDocument;
import com.claimpilot.claim.domain.ClaimStatus;
import com.claimpilot.claim.domain.Settlement;
import com.claimpilot.claim.domain.Transition;
import com.claimpilot.fraud.domain.PartyRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository {

    String nextNumber();

    void insert(Claim c);

    Optional<Claim> find(UUID id);

    /** Row lock for the rest of the transaction; every workflow step takes it, so steps on one claim serialise. */
    Optional<Claim> lock(UUID id);

    /** Writes the mutable fields and bumps lock_version. */
    Claim save(Claim c);

    List<Claim> list(ClaimStatus status, int limit);

    List<Claim> forSubject(String subject, ClaimStatus status, int limit);

    long otherClaimsBetween(UUID policyId, UUID exclude, Instant from, Instant to);

    void insertParties(UUID claimId, List<PartyRef> parties);

    List<PartyRef> parties(UUID claimId);

    void insertDocuments(List<ClaimDocument> docs);

    List<ClaimDocument> documents(UUID claimId);

    boolean markUploaded(UUID documentId);

    void transition(UUID claimId, Transition t);

    List<Transition> transitions(UUID claimId);

    List<ApprovalChain.Signoff> approvals(UUID claimId);

    void insertApproval(UUID claimId, ApprovalChain.Signoff s, long amountMinor, Instant at);

    /** False if the claim already has a settlement. */
    boolean insertSettlement(Settlement s);

    Optional<Settlement> settlement(UUID claimId);
}
