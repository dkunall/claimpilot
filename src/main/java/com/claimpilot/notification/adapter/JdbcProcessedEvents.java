package com.claimpilot.notification.adapter;

import com.claimpilot.notification.application.ProcessedEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Repository
class JdbcProcessedEvents implements ProcessedEvents {

    private final JdbcClient db;
    private final Clock clock;

    JdbcProcessedEvents(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public boolean markProcessed(String consumer, UUID eventId) {
        return db.sql("insert into processed_event (consumer, event_id, at) values (?, ?, ?) on conflict do nothing")
                .params(consumer, eventId, Timestamp.from(clock.instant())).update() == 1;
    }
}
