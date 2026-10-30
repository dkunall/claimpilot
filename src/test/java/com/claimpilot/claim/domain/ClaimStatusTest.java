package com.claimpilot.claim.domain;

import com.claimpilot.shared.domain.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static com.claimpilot.claim.domain.ClaimStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimStatusTest {

    @Test
    void onlyTheDrawnEdgesExist() {
        int edges = 0;
        for (ClaimStatus from : values()) {
            for (ClaimStatus to : values()) {
                if (from.canMoveTo(to)) {
                    edges++;
                }
            }
        }
        assertThat(edges).isEqualTo(10);
        assertThat(FNOL.canMoveTo(TRIAGE)).isTrue();
        assertThat(TRIAGE.canMoveTo(STP_APPROVED)).isTrue();
        assertThat(ASSESSMENT.canMoveTo(INVESTIGATION)).isTrue();
        assertThat(INVESTIGATION.canMoveTo(APPROVED)).isTrue();
        assertThat(STP_APPROVED.canMoveTo(SETTLED)).isTrue();
    }

    @Test
    void noShortcutsToMoney() {
        for (ClaimStatus s : EnumSet.of(FNOL, TRIAGE, ASSESSMENT, INVESTIGATION, REJECTED, SETTLED)) {
            assertThat(s.canMoveTo(SETTLED)).as(s.name()).isFalse();
        }
        assertThatThrownBy(() -> FNOL.moveTo(APPROVED)).isInstanceOf(DomainConflictException.class)
                .hasMessageContaining("FNOL claim can't become APPROVED");
        assertThat(SETTLED.terminal()).isTrue();
        assertThat(REJECTED.terminal()).isTrue();
    }

    @Test
    void everyStateCanReachAnEnd() {
        for (ClaimStatus s : values()) {
            assertThat(reachesEnd(s, 0)).as(s.name()).isTrue();
        }
    }

    private static boolean reachesEnd(ClaimStatus s, int depth) {
        if (s.terminal()) {
            return true;
        }
        for (ClaimStatus n : values()) {
            if (s.canMoveTo(n) && depth < 10 && reachesEnd(n, depth + 1)) {
                return true;
            }
        }
        return false;
    }
}
