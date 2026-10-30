package com.claimpilot.claim.domain;

import com.claimpilot.claim.domain.ApprovalChain.Limits;
import com.claimpilot.claim.domain.ApprovalChain.Request;
import com.claimpilot.claim.domain.ApprovalChain.Signoff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalChainTest {

    static final Limits LIMITS = new Limits(5_000_000, 50_000_000);
    static final ApprovalChain CHAIN = ApprovalChain.standard();
    static final Signoff ASSESSOR = new Signoff("ana", Authority.ASSESSOR);
    static final Signoff MANAGER = new Signoff("mo", Authority.MANAGER);
    static final Signoff MANAGER_2 = new Signoff("meera", Authority.MANAGER);

    static ApprovalDecision decide(long amount, Signoff who, String assessor, Signoff... earlier) {
        return CHAIN.decide(new Request(amount, who, assessor, List.of(earlier), LIMITS));
    }

    @Test
    void anAssessorApprovesTheirOwnSmallFigure() {
        assertThat(decide(5_000_000, ASSESSOR, "ana")).isInstanceOf(ApprovalDecision.Approved.class);
    }

    @Test
    void aboveTheAssessorLimitTheAssessorEscalatesAndCantSelfApprove() {
        assertThat(decide(5_000_001, new Signoff("bo", Authority.ASSESSOR), "ana"))
                .isEqualTo(new ApprovalDecision.Escalated(Authority.MANAGER,
                        "over the assessor limit 5000000; a manager must approve"));
        assertThat(decide(5_000_001, ASSESSOR, "ana")).isInstanceOfSatisfying(ApprovalDecision.Denied.class,
                d -> assertThat(d.code()).isEqualTo("four-eyes"));
        // a manager who did the assessment is held to four eyes too
        assertThat(decide(6_000_000, MANAGER, "mo")).isInstanceOfSatisfying(ApprovalDecision.Denied.class,
                d -> assertThat(d.code()).isEqualTo("four-eyes"));
    }

    @Test
    void aManagerApprovesUpToTheirLimit() {
        assertThat(decide(50_000_000, MANAGER, "ana")).isInstanceOf(ApprovalDecision.Approved.class);
    }

    @Test
    void aboveTheManagerLimitTwoDifferentManagersMustSign() {
        assertThat(decide(50_000_001, MANAGER, "ana")).isInstanceOf(ApprovalDecision.Escalated.class);
        assertThat(decide(50_000_001, MANAGER_2, "ana", MANAGER)).isInstanceOf(ApprovalDecision.Approved.class);
        // an assessor's earlier recommendation doesn't count as the second manager
        assertThat(decide(50_000_001, MANAGER, "ana", ASSESSOR)).isInstanceOf(ApprovalDecision.Escalated.class);
    }

    @Test
    void nobodySignsTwice() {
        assertThat(decide(50_000_001, MANAGER, "ana", MANAGER)).isInstanceOfSatisfying(ApprovalDecision.Denied.class,
                d -> assertThat(d.code()).isEqualTo("already-signed"));
    }

    @Test
    void anEmptyChainDenies() {
        var empty = new ApprovalChain(List.of());
        assertThat(empty.decide(new Request(1, MANAGER, "ana", List.of(), LIMITS)))
                .isInstanceOf(ApprovalDecision.Denied.class);
    }
}
