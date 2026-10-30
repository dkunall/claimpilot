package com.claimpilot.fraud.adapter;

import com.claimpilot.fraud.application.FraudService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "claimpilot.jobs.enabled", havingValue = "true", matchIfMissing = true)
class FraudRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(FraudRebuildJob.class);

    private final FraudService fraud;

    FraudRebuildJob(FraudService fraud) {
        this.fraud = fraud;
    }

    @EventListener(ApplicationReadyEvent.class)
    void atStartup() {
        rebuild();
    }

    @Scheduled(fixedDelayString = "${claimpilot.fraud.rebuild-interval}", initialDelayString = "${claimpilot.fraud.rebuild-interval}")
    void rebuild() {
        try {
            fraud.rebuild();
        } catch (RuntimeException e) {
            log.warn("fraud graph rebuild failed; keeping the current graph: {}", e.toString());
        }
    }
}
