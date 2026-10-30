package com.claimpilot.policy.application;

import com.claimpilot.policy.domain.EndorsementChange;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.policy.domain.PolicyStatus;
import com.claimpilot.policy.domain.PolicyVersion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository {

    void insert(Policy p);

    Optional<Policy> find(UUID id);

    List<Policy> forSubject(String subject);

    /** Rows we currently believe, in valid-time order. */
    List<PolicyVersion> current(UUID policyId);

    /** The row valid at {@code validAt} as recorded at {@code asOf}. */
    Optional<PolicyVersion> at(UUID policyId, Instant validAt, Instant asOf);

    List<PolicyVersion> history(UUID policyId);

    /** Optimistic lock: bumps the version only if it is still {@code expected}. */
    boolean bump(UUID id, long expected);

    /** Ends the record-time of current rows; false if any of them was no longer current. */
    boolean close(List<Long> ids, Instant recordedAt);

    void insertVersions(UUID policyId, List<PolicyVersion> rows);

    void insertEndorsement(UUID id, UUID policyId, Instant effectiveFrom, EndorsementChange change,
                           long premiumDeltaMinor, Instant recordedAt, String createdBy);

    void setStatus(UUID id, PolicyStatus status, Instant graceUntil);
}
