package com.claimpilot.outbox.application;

import java.util.Map;

/**
 * Port for events that must leave the process exactly when the state change commits. Implementations write to
 * the outbox table in the caller's transaction; a relay publishes them afterwards.
 */
public interface Outbox {

    String POLICY_EVENTS = "claimpilot.policy-events";
    String CLAIM_EVENTS = "claimpilot.claim-events";

    void append(String topic, String key, String type, Map<String, Object> data);
}
