package com.claimpilot.claim.domain;

import com.claimpilot.fraud.domain.FraudScore;
import com.claimpilot.product.domain.ClaimRules;
import com.claimpilot.product.domain.Coverage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TriageTest {

    static final ClaimRules RULES = new ClaimRules(2_500_000, 30, 30, 90, 1, 0.3, 0.6);
    static final Coverage HOSPITAL = new Coverage("HOSPITALISATION", "Hospitalisation", 50_000_000, 500_000);
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant LOSS = START.plus(Duration.ofDays(100));

    static Triage.Facts facts(long claimed, Instant since, Instant reported, long prior, long docs, double fraud) {
        return new Triage.Facts(claimed, HOSPITAL, LOSS, reported, since, prior, docs,
                new FraudScore(fraud, List.of()));
    }

    @Test
    void aCleanSmallClaimGoesStraightThroughLessTheDeductible() {
        var r = Triage.decide(facts(2_000_000, START, LOSS.plus(Duration.ofDays(2)), 0, 1, 0.0), RULES);
        assertThat(r.stp()).isTrue();
        assertThat(r.investigate()).isFalse();
        assertThat(r.reserveMinor()).isEqualTo(1_500_000);
        assertThat(r.reasons()).containsExactly("all straight-through rules passed");
    }

    @Test
    void everyFailedRuleIsListed() {
        var r = Triage.decide(facts(60_000_000, LOSS.minus(Duration.ofDays(5)), LOSS.plus(Duration.ofDays(40)), 2, 0,
                0.45), RULES);
        assertThat(r.stp()).isFalse();
        assertThat(r.investigate()).isFalse();
        assertThat(r.reserveMinor()).isEqualTo(49_500_000);
        assertThat(r.reasons()).hasSize(7);
        assertThat(String.join("|", r.reasons())).contains("straight-through limit", "coverage limit",
                "waiting period", "reported 40 days", "2 other claim", "0 of 1 required", "fraud score 0.45");
    }

    @Test
    void boundariesAreInclusiveWhereTheRuleSaysSo() {
        // exactly at the STP limit, exactly 30 days in, reported exactly 30 days later: still STP
        var r = Triage.decide(new Triage.Facts(2_500_000, HOSPITAL, LOSS, LOSS.plus(Duration.ofDays(30)),
                LOSS.minus(Duration.ofDays(30)), 0, 1, new FraudScore(0.29, List.of())), RULES);
        assertThat(r.stp()).isTrue();
        // fraud score exactly at the STP ceiling is not STP
        var atCeiling = Triage.decide(facts(1_000_000, START, LOSS, 0, 1, 0.3), RULES);
        assertThat(atCeiling.stp()).isFalse();
    }

    @Test
    void aHighFraudScoreSendsItToInvestigation() {
        var r = Triage.decide(facts(1_000_000, START, LOSS, 0, 1, 0.6), RULES);
        assertThat(r.stp()).isFalse();
        assertThat(r.investigate()).isTrue();
    }

    @Test
    void aClaimUnderTheDeductiblePaysNothingAndIsNotStp() {
        var r = Triage.decide(facts(400_000, START, LOSS, 0, 1, 0), RULES);
        assertThat(r.reserveMinor()).isZero();
        assertThat(r.stp()).isFalse();
    }
}
