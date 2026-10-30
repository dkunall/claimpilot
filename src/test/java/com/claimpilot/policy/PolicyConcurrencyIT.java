package com.claimpilot.policy;

import com.claimpilot.support.Api;
import com.claimpilot.support.ItBase;
import com.claimpilot.support.Race;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyConcurrencyIT extends ItBase {

    @Test
    void racingBindsOfOneQuoteIssueOnePolicyAndChargeOnce() {
        Api c = as(unique("cust"));
        String q = c.post("/v1/quotes", quoteBody("health-shield", healthInputs(40_000_000, 35))).expect(201).text("id");
        int n = 12;
        List<Integer> codes = Race.run(n, i -> c.post("/v1/quotes/" + q + "/bind",
                Map.of("paymentToken", "tok_visa", "autopay", true), key("race-" + i)).status());

        assertThat(codes).filteredOn(s -> s == 201).hasSize(1);
        assertThat(codes).filteredOn(s -> s == 409).hasSize(n - 1);
        assertThat(db.sql("select count(*) from policy p join quote q on q.policy_id = p.id where q.id = ?::uuid")
                .param(q).query(Long.class).single()).isEqualTo(1);
        assertThat(gateway.approved("charge:bind:" + q)).isEqualTo(1);
        assertThat(db.sql("select count(*) from payment where gateway_key = ?").param("bind:" + q)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sameKeyRetriedInParallelRunsOnce() {
        Api c = as(unique("cust"));
        String q = c.post("/v1/quotes", quoteBody("health-shield", healthInputs(40_000_000, 35))).expect(201).text("id");
        List<Api.Response> rs = Race.run(10, i -> c.post("/v1/quotes/" + q + "/bind",
                Map.of("paymentToken", "tok_visa", "autopay", true), key("one-key")));
        // one does the work; the rest replay it, or are told it is still in flight
        assertThat(rs).allSatisfy(r -> assertThat(r.status()).isIn(200, 201, 409));
        assertThat(rs).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(rs).filteredOn(r -> r.status() == 200)
                .allSatisfy(r -> assertThat(r.header("Idempotent-Replayed")).isEqualTo("true"));
        assertThat(gateway.approved("charge:bind:" + q)).isEqualTo(1);
    }

    @Test
    void racingEndorsementsLeaveOneConsistentHistoryAndNoStrayMoney() {
        Holder h = healthHolder(30_000_000, null);
        String id = h.policyId();
        Instant start = Instant.parse(h.policy().path("termStart").asText());
        int n = 8;
        List<Api.Response> rs = Race.run(n, i -> h.api().post("/v1/policies/" + id + "/endorsements",
                Map.of("effectiveFrom", start.plus(Duration.ofDays(30 + i)).toString(),
                        "sumInsuredMinor", 31_000_000 + i * 1_000_000L), key("end-" + i)));

        List<Api.Response> won = rs.stream().filter(r -> r.status() == 201).toList();
        assertThat(won).isNotEmpty();
        assertThat(rs).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));

        // current rows tile the term exactly: no gap, no overlap
        List<Instant[]> cur = db.sql("""
                select lower(valid) f, upper(valid) t from policy_version
                where policy_id = ?::uuid and upper_inf(recorded) order by lower(valid)""")
                .param(id).query((rs1, i) -> new Instant[] {rs1.getTimestamp(1).toInstant(), rs1.getTimestamp(2).toInstant()})
                .list();
        for (int i = 1; i < cur.size(); i++) {
            assertThat(cur.get(i)[0]).isEqualTo(cur.get(i - 1)[1]);
        }
        assertThat(cur.getFirst()[0]).isEqualTo(start);
        assertThat(cur.getLast()[1]).isEqualTo(Instant.parse(h.policy().path("termEnd").asText()));

        // money: every winner's delta is charged once; losers that were charged got the same amount back
        long winners = won.stream().mapToLong(r -> r.body().path("premiumDeltaMinor").asLong()).sum();
        Long net = db.sql("""
                select coalesce(sum(amount_minor), 0) from payment
                where policy_id = ?::uuid and kind in ('ENDORSEMENT', 'REFUND') and status = 'SUCCEEDED'""")
                .param(id).query(Long.class).single();
        assertThat(net).isEqualTo(winners);
        assertThat(db.sql("select count(*) from endorsement where policy_id = ?::uuid").param(id)
                .query(Long.class).single()).isEqualTo(won.size());
        assertThat(h.api().get("/v1/policies/" + id).expect(200).body().path("lockVersion").asLong())
                .isEqualTo(won.size());
    }
}
