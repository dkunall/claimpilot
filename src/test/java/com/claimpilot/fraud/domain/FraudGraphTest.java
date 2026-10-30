package com.claimpilot.fraud.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FraudGraphTest {

    static PartyRef phone(String h) {
        return new PartyRef(PartyType.PHONE, h);
    }

    static PartyRef bank(String h) {
        return new PartyRef(PartyType.BANK_ACCOUNT, h);
    }

    static PartyRef garage(String h) {
        return new PartyRef(PartyType.GARAGE, h);
    }

    @Test
    void holdersSharingPersonalIdentifiersFormOneSetTransitively() {
        var g = new FraudGraph();
        g.add("a", List.of(phone("p1")));
        g.add("b", List.of(phone("p1"), bank("b1")));
        g.add("c", List.of(bank("b1")));
        g.add("d", List.of(phone("p9")));
        assertThat(g.linkedHolders("a")).containsExactlyInAnyOrder("b", "c");
        assertThat(g.linkedHolders("d")).isEmpty();
        assertThat(g.clusters()).isEqualTo(1);
        var s = g.score("a", List.of(phone("p1")));
        assertThat(s.score()).isEqualTo(0.6);
        assertThat(s.reasons()).containsExactly("shares a phone, bank account or device with 2 other holder(s)");
    }

    @Test
    void aBusyGarageIsAWeakSignalAndNeverMergesPeople() {
        var g = new FraudGraph();
        for (int i = 0; i < 50; i++) {
            g.add("h" + i, List.of(garage("g1"), phone("own" + i)));
        }
        assertThat(g.clusters()).isZero();
        assertThat(g.linkedHolders("h0")).isEmpty();
        assertThat(g.score("h0", List.of(garage("g1"))).score()).isEqualTo(0.1);
    }

    @Test
    void aRingThatAlsoUsesTheSameGarageScoresHigher() {
        var g = new FraudGraph();
        g.add("a", List.of(bank("b1"), garage("g1")));
        g.add("b", List.of(bank("b1"), garage("g1")));
        var s = g.score("a", List.of(bank("b1"), garage("g1")));
        // 0.3 link + 0.1 * 1/10 hub + 0.2 collusion
        assertThat(s.score()).isEqualTo(0.51);
        assertThat(s.reasons()).hasSize(3);
    }

    @Test
    void theScoreIsCappedAtOne() {
        var g = new FraudGraph();
        for (int i = 0; i < 20; i++) {
            g.add("r" + i, List.of(phone("shared"), garage("g")));
        }
        assertThat(g.score("r0", List.of(phone("shared"), garage("g"))).score()).isEqualTo(1.0);
    }

    @Test
    void matchesANaiveComponentSearchOnRandomGraphs() {
        var rnd = new Random(7);
        for (int round = 0; round < 50; round++) {
            var g = new FraudGraph();
            List<FraudGraph.Edge> edges = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String h = "h" + rnd.nextInt(25);
                var p = rnd.nextBoolean() ? phone("p" + rnd.nextInt(30)) : bank("b" + rnd.nextInt(30));
                edges.add(new FraudGraph.Edge(h, p));
                g.add(h, List.of(p));
            }
            var rebuilt = FraudGraph.of(edges);
            for (int i = 0; i < 25; i++) {
                String h = "h" + i;
                Set<String> expected = naive(edges, h);
                assertThat(g.linkedHolders(h)).as(h).isEqualTo(expected);
                assertThat(rebuilt.linkedHolders(h)).isEqualTo(expected);
            }
        }
    }

    /** Breadth-first search over holder-party edges. */
    private static Set<String> naive(List<FraudGraph.Edge> edges, String start) {
        if (edges.stream().noneMatch(e -> e.holder().equals(start))) {
            return Set.of();
        }
        Set<String> holders = new java.util.HashSet<>(Set.of(start));
        boolean grew = true;
        while (grew) {
            grew = false;
            Set<PartyRef> parties = new java.util.HashSet<>();
            edges.stream().filter(e -> holders.contains(e.holder())).forEach(e -> parties.add(e.party()));
            for (var e : edges) {
                if (parties.contains(e.party()) && holders.add(e.holder())) {
                    grew = true;
                }
            }
        }
        holders.remove(start);
        return holders;
    }
}
