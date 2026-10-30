package com.claimpilot.notification.adapter;

import com.claimpilot.notification.application.NotificationService;
import com.claimpilot.outbox.application.EventEnvelope;
import com.claimpilot.outbox.application.Outbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka side of notifications. A record that isn't an envelope is logged and skipped rather than retried. */
@Component
@ConditionalOnProperty(name = "claimpilot.jobs.enabled", havingValue = "true", matchIfMissing = true)
class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationService notifications;
    private final ObjectMapper json;

    NotificationListener(NotificationService notifications, ObjectMapper json) {
        this.notifications = notifications;
        this.json = json;
    }

    @KafkaListener(topics = {Outbox.POLICY_EVENTS, Outbox.CLAIM_EVENTS}, groupId = "claimpilot-notifications")
    void on(String payload) {
        EventEnvelope event;
        try {
            event = json.readValue(payload, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            log.error("skipping a record that isn't an event envelope ({} chars)", payload.length());
            return;
        }
        notifications.handle(event);
    }
}
