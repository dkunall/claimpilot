package com.claimpilot.claim.domain;

import java.time.Instant;
import java.util.UUID;

/** A file the claimant uploads straight to object storage. It counts once we've seen it there at the declared size. */
public record ClaimDocument(UUID id, UUID claimId, String objectKey, String fileName, String contentType,
                            long sizeBytes, boolean uploaded, Instant createdAt) {
}
