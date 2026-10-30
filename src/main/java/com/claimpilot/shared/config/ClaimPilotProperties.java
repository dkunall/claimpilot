package com.claimpilot.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("claimpilot")
public record ClaimPilotProperties(
        Security security,
        Storage storage,
        Mail mail,
        Quotes quotes,
        Renewals renewals,
        Fraud fraud,
        Jobs jobs) {

    /** {@code partyHmacKey} keys the hashes of phone numbers and bank accounts; it never leaves the process. */
    public record Security(String partyHmacKey, List<String> bootstrapManagers) {
        public Security {
            bootstrapManagers = bootstrapManagers == null ? List.of() : List.copyOf(bootstrapManagers);
        }
    }

    public record Storage(String endpoint, String publicEndpoint, String region, String bucket, String accessKey,
                          String secretKey, Duration presignTtl, long maxDocumentBytes) {
    }

    public record Mail(String from) {
    }

    public record Quotes(int perMinute, int burst, Duration validity) {
    }

    public record Renewals(int gridSize, int chunkSize, int graceDays, int reminderDaysBefore) {
    }

    public record Fraud(Duration rebuildInterval) {
    }

    public record Jobs(boolean enabled) {
    }
}
