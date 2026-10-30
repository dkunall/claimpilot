package com.claimpilot.fraud.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who is connected to whom through claims. Holders and their personal identifiers (phone, bank account,
 * device) are nodes in a union-find, so holders that share any of them end up in one set: a possible ring.
 * Hubs (garages, hospitals) are kept apart as weighted signals, because a busy garage would otherwise glue
 * every honest customer into one giant cluster.
 *
 * <p>Score = 0.3 per other holder in the set (capped at 0.9), plus up to 0.1 per shared hub scaled by how many
 * other holders used it (capped at 0.15 in total), plus 0.2 when another holder in the same set used the same
 * hub. Capped at 1. The weights are a starting point, not a trained model.
 */
public final class FraudGraph {

    static final double PER_LINKED_HOLDER = 0.3;
    static final double LINK_CAP = 0.9;
    static final double HUB_MAX = 0.1;
    static final double HUB_CAP = 0.15;
    static final int HUB_SATURATION = 10;
    static final double COLLUSION = 0.2;

    private final UnionFind sets = new UnionFind();
    /** root -> holders in that set; kept in step with unions so a lookup is O(1). */
    private final Map<String, Set<String>> holdersByRoot = new HashMap<>();
    private final Map<PartyRef, Set<String>> hubHolders = new HashMap<>();

    public record Edge(String holder, PartyRef party) {
    }

    public static FraudGraph of(Collection<Edge> edges) {
        FraudGraph g = new FraudGraph();
        for (Edge e : edges) {
            g.add(e.holder(), List.of(e.party()));
        }
        return g;
    }

    public synchronized void add(String holder, Collection<PartyRef> parties) {
        String h = holderNode(holder);
        String root = sets.find(h);
        holdersByRoot.computeIfAbsent(root, r -> new HashSet<>()).add(holder);
        for (PartyRef p : parties) {
            if (p.type().personal()) {
                link(h, p.node());
            } else {
                hubHolders.computeIfAbsent(p, k -> new HashSet<>()).add(holder);
            }
        }
    }

    private void link(String a, String b) {
        String ra = sets.find(a);
        String rb = sets.find(b);
        if (ra.equals(rb)) {
            return;
        }
        String root = sets.union(ra, rb);
        String gone = root.equals(ra) ? rb : ra;
        Set<String> moved = holdersByRoot.remove(gone);
        if (moved != null) {
            holdersByRoot.computeIfAbsent(root, r -> new HashSet<>()).addAll(moved);
        }
    }

    /** Other holders linked to {@code holder} by a shared phone, bank account or device. */
    public synchronized Set<String> linkedHolders(String holder) {
        String h = holderNode(holder);
        if (!sets.contains(h)) {
            return Set.of();
        }
        Set<String> all = new HashSet<>(holdersByRoot.getOrDefault(sets.find(h), Set.of()));
        all.remove(holder);
        return all;
    }

    /** Sets with two or more holders. */
    public synchronized int clusters() {
        return (int) holdersByRoot.values().stream().filter(s -> s.size() > 1).count();
    }

    public synchronized FraudScore score(String holder, Collection<PartyRef> claimParties) {
        List<String> reasons = new ArrayList<>();
        Set<String> linked = linkedHolders(holder);
        double score = 0;
        if (!linked.isEmpty()) {
            score += Math.min(LINK_CAP, PER_LINKED_HOLDER * linked.size());
            reasons.add("shares a phone, bank account or device with " + linked.size() + " other holder(s)");
        }
        double hub = 0;
        boolean collusion = false;
        for (PartyRef p : claimParties) {
            if (p.type().personal()) {
                continue;
            }
            Set<String> others = new HashSet<>(hubHolders.getOrDefault(p, Set.of()));
            others.remove(holder);
            if (others.isEmpty()) {
                continue;
            }
            hub += HUB_MAX * Math.min(1.0, others.size() / (double) HUB_SATURATION);
            reasons.add(p.type().name().toLowerCase() + " used by " + others.size() + " other holder(s)");
            if (others.stream().anyMatch(linked::contains)) {
                collusion = true;
            }
        }
        score += Math.min(HUB_CAP, hub);
        if (collusion) {
            score += COLLUSION;
            reasons.add("a linked holder used the same garage or hospital");
        }
        return new FraudScore(Math.min(1.0, Math.round(score * 1000) / 1000.0), reasons);
    }

    private static String holderNode(String holder) {
        return "HOLDER:" + holder;
    }
}
