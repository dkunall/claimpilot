package com.claimpilot.claim.adapter;

import com.claimpilot.claim.application.ApprovalLimits;
import com.claimpilot.claim.domain.ApprovalChain;
import com.claimpilot.product.domain.LineOfBusiness;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

@Repository
class JdbcApprovalLimits implements ApprovalLimits {

    private final JdbcClient db;

    JdbcApprovalLimits(JdbcClient db) {
        this.db = db;
    }

    @Override
    public ApprovalChain.Limits forLine(LineOfBusiness line) {
        Map<String, Long> byRole = new HashMap<>();
        db.sql("select role, max_amount_minor from approval_limit where line = ?").param(line.name())
                .query(rs -> {
                    byRole.put(rs.getString(1), rs.getLong(2));
                });
        if (!byRole.containsKey("ASSESSOR") || !byRole.containsKey("MANAGER")) {
            throw new IllegalStateException("approval_limit has no ASSESSOR/MANAGER rows for " + line);
        }
        return new ApprovalChain.Limits(byRole.get("ASSESSOR"), byRole.get("MANAGER"));
    }
}
