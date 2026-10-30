package com.claimpilot.fraud.adapter;

import com.claimpilot.fraud.application.FraudEdges;
import com.claimpilot.fraud.domain.FraudGraph;
import com.claimpilot.fraud.domain.PartyRef;
import com.claimpilot.fraud.domain.PartyType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
class JdbcFraudEdges implements FraudEdges {

    private final JdbcClient db;

    JdbcFraudEdges(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<FraudGraph.Edge> all() {
        return db.sql("""
                select distinct p.holder_subject, cp.party_type, cp.party_key_hash
                from claim_party cp join claim c on c.id = cp.claim_id join policy p on p.id = c.policy_id""")
                .query((rs, i) -> new FraudGraph.Edge(rs.getString(1),
                        new PartyRef(PartyType.valueOf(rs.getString(2)), rs.getString(3))))
                .list();
    }
}
