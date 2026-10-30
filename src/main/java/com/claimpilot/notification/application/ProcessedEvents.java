package com.claimpilot.notification.application;

import java.util.UUID;

/** Consumer-side dedup: Kafka delivers at least once, so every handler records the event ids it has acted on. */
public interface ProcessedEvents {

    /** True the first time {@code consumer} sees {@code eventId}; must run in the handler's transaction. */
    boolean markProcessed(String consumer, UUID eventId);
}
