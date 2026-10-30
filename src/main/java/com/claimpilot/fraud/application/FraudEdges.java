package com.claimpilot.fraud.application;

import com.claimpilot.fraud.domain.FraudGraph;

import java.util.List;

/** Port: every (holder, party) pair ever seen on a claim, for rebuilding the graph from the source of truth. */
public interface FraudEdges {

    List<FraudGraph.Edge> all();
}
