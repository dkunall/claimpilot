package com.claimpilot.fraud.application;

import com.claimpilot.fraud.domain.FraudGraph;
import com.claimpilot.fraud.domain.FraudScore;
import com.claimpilot.fraud.domain.PartyRef;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Holds the in-memory fraud graph. Each claim adds its edges and is scored against everything seen so far. A
 * periodic rebuild from the database replaces the graph, so it converges on the source of truth and picks up
 * claims taken by other instances; edges added while a rebuild is loading are replayed onto the new graph.
 */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    private final FraudEdges edges;
    private final Object lock = new Object();
    private FraudGraph graph = new FraudGraph();
    private boolean rebuilding;
    private final List<FraudGraph.Edge> addedDuringRebuild = new ArrayList<>();

    public FraudService(FraudEdges edges, MeterRegistry meters) {
        this.edges = edges;
        Gauge.builder("claimpilot.fraud.clusters", this, FraudService::clusters)
                .description("sets of two or more holders linked by a phone, bank account or device")
                .register(meters);
    }

    public FraudScore addAndScore(String holder, Collection<PartyRef> parties) {
        synchronized (lock) {
            graph.add(holder, parties);
            if (rebuilding) {
                parties.forEach(p -> addedDuringRebuild.add(new FraudGraph.Edge(holder, p)));
            }
            return graph.score(holder, parties);
        }
    }

    public int clusters() {
        synchronized (lock) {
            return graph.clusters();
        }
    }

    public int rebuild() {
        synchronized (lock) {
            rebuilding = true;
            addedDuringRebuild.clear();
        }
        FraudGraph fresh;
        try {
            fresh = FraudGraph.of(edges.all());
        } catch (RuntimeException e) {
            synchronized (lock) {
                rebuilding = false;
                addedDuringRebuild.clear();
            }
            throw e;
        }
        synchronized (lock) {
            for (FraudGraph.Edge e : addedDuringRebuild) {
                fresh.add(e.holder(), List.of(e.party()));
            }
            graph = fresh;
            rebuilding = false;
            addedDuringRebuild.clear();
            int c = graph.clusters();
            log.info("fraud graph rebuilt: {} linked clusters", c);
            return c;
        }
    }
}
