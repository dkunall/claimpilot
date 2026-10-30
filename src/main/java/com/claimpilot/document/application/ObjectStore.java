package com.claimpilot.document.application;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Port to S3-compatible storage. Bytes from customers go straight to it via presigned URLs, not through us. */
public interface ObjectStore {

    void put(String key, byte[] content, String contentType);

    Presigned presignPut(String key, String contentType, long sizeBytes, Duration ttl);

    Presigned presignGet(String key, Duration ttl);

    Optional<Long> sizeOf(String key);

    record Presigned(URI url, Map<String, List<String>> headers, Instant expiresAt) {
    }
}
