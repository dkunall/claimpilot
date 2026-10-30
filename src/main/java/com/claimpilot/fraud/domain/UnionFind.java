package com.claimpilot.fraud.domain;

import java.util.HashMap;
import java.util.Map;

/** Disjoint sets with path halving and union by size. Not thread-safe; {@link FraudGraph} guards it. */
final class UnionFind {

    private final Map<String, String> parent = new HashMap<>();
    private final Map<String, Integer> size = new HashMap<>();

    String find(String x) {
        parent.putIfAbsent(x, x);
        size.putIfAbsent(x, 1);
        String cur = x;
        while (!parent.get(cur).equals(cur)) {
            String grand = parent.get(parent.get(cur));
            parent.put(cur, grand);
            cur = grand;
        }
        return cur;
    }

    /** Returns the surviving root. */
    String union(String a, String b) {
        String ra = find(a);
        String rb = find(b);
        if (ra.equals(rb)) {
            return ra;
        }
        if (size.get(ra) < size.get(rb)) {
            String t = ra;
            ra = rb;
            rb = t;
        }
        parent.put(rb, ra);
        size.put(ra, size.get(ra) + size.get(rb));
        return ra;
    }

    boolean contains(String x) {
        return parent.containsKey(x);
    }
}
