package com.claimpilot.claim;

import com.claimpilot.support.Api;
import com.claimpilot.support.ItBase;
import com.claimpilot.support.Race;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimIT extends ItBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");

    /** Moves a fresh policy's cover back in time so it is past the waiting period. Test fixture only. */
    private void age(Holder h, int days) {
        UUID id = UUID.fromString(h.policyId());
        String i = days + " days";
        db.sql("""
                update policy set first_start = first_start - cast(? as interval), term_start = term_start - cast(? as interval),
                    term_end = term_end - cast(? as interval) where id = ?""").params(i, i, i, id).update();
        db.sql("""
                update policy_version set valid = tstzrange(lower(valid) - cast(? as interval), upper(valid) - cast(? as interval), '[)')
                where policy_id = ?""").params(i, i, id).update();
    }

    private Holder aged(long sumInsured) {
        Holder h = healthHolder(sumInsured, null);
        age(h, 60);
        return h;
    }

    private static Map<String, Object> fnol(Holder h, long claimed, List<Map<String, String>> parties, String payout) {
        var b = new LinkedHashMap<String, Object>();
        b.put("policyId", h.policyId());
        b.put("coverageCode", "HOSPITALISATION");
        b.put("lossAt", Instant.now().minus(Duration.ofDays(2)).toString());
        b.put("claimedMinor", claimed);
        b.put("description", "Two nights in hospital (synthetic)");
        b.put("parties", parties);
        b.put("payoutToken", payout);
        b.put("documents", List.of(Map.of("fileName", "bill.pdf", "contentType", "application/pdf", "sizeBytes", 9)));
        return b;
    }

    private JsonNode report(Holder h, long claimed, List<Map<String, String>> parties) {
        return h.api().post("/v1/claims", fnol(h, claimed, parties, "tok_payout_" + h.subject()),
                key("fnol-" + UUID.randomUUID())).expect(201).body();
    }

    /** PUTs the bytes to the presigned URL, then tells the API. Returns the claim after auto-triage. */
    private JsonNode upload(Holder h, JsonNode fnol) throws Exception {
        JsonNode u = fnol.path("uploads").get(0);
        var req = HttpRequest.newBuilder(URI.create(u.path("url").asText()));
        u.path("headers").properties().forEach(e -> {
            if (!RESTRICTED.contains(e.getKey().toLowerCase())) {
                e.getValue().forEach(v -> req.header(e.getKey(), v.asText()));
            }
        });
        var put = HTTP.send(req.PUT(HttpRequest.BodyPublishers.ofString("%PDF-1.4\n")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
        String claim = fnol.path("claim").path("id").asText();
        return h.api().post("/v1/claims/" + claim + "/documents/" + u.path("documentId").asText() + "/uploaded", Map.of())
                .expect(200).body();
    }

    private JsonNode inAssessment(Holder h, long claimed) throws Exception {
        JsonNode c = upload(h, report(h, claimed, List.of()));
        assertThat(c.path("status").asText()).isEqualTo("ASSESSMENT");
        return c;
    }

    private static Map<String, String> party(String type, String value) {
        return Map.of("type", type, "value", value);
    }

    @Test
    void smallCleanClaimGoesStraightThroughAndIsPaidOnce() throws Exception {
        Holder h = aged(36_500_000);
        JsonNode fnol = report(h, 1_200_000, List.of(party("PHONE", "+91 90000 " + (10000 + (int) (Math.random() * 89999)))));
        assertThat(fnol.path("claim").path("status").asText()).isEqualTo("FNOL");
        assertThat(fnol.path("claim").path("fraudScore").isNull()).isTrue(); // customers don't see fraud fields

        JsonNode c = upload(h, fnol);
        String id = c.path("id").asText();
        assertThat(c.path("status").asText()).isEqualTo("SETTLED");
        assertThat(c.path("straightThrough").asBoolean()).isTrue();
        assertThat(c.path("settlement").path("amountMinor").asLong()).isEqualTo(1_200_000);
        List<String> path = new ArrayList<>();
        c.path("history").forEach(t -> path.add(t.path("to").asText()));
        assertThat(path).containsExactly("FNOL", "TRIAGE", "STP_APPROVED", "SETTLED");

        // the same "uploaded" call again, and an explicit settle retry, don't pay again
        String doc = c.path("documents").get(0).path("id").asText();
        h.api().post("/v1/claims/" + id + "/documents/" + doc + "/uploaded", Map.of()).expect(200);
        staff("MANAGER").post("/v1/claims/" + id + "/settle", Map.of()).expect(200);
        assertThat(gateway.approved("payout:settle:" + id)).isEqualTo(1);
        assertThat(db.sql("select count(*) from settlement where claim_id = ?").param(UUID.fromString(id))
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void waitingPeriodAndLimitsSendAClaimToAnAssessorWithReasons() throws Exception {
        Holder fresh = healthHolder(36_500_000, null);
        age(fresh, 5); // loss two days ago, three days into cover: inside the 30-day waiting period
        JsonNode c = upload(fresh, report(fresh, 1_000_000, List.of()));
        assertThat(c.path("status").asText()).isEqualTo("ASSESSMENT");
        assertThat(c.path("triageReasons").toString()).contains("waiting period");

        Holder old = aged(36_500_000);
        JsonNode big = upload(old, report(old, 3_000_000, List.of()));
        assertThat(big.path("status").asText()).isEqualTo("ASSESSMENT");
        assertThat(big.path("triageReasons").toString()).contains("straight-through limit");
    }

    @Test
    void fnolRulesAndVisibility() throws Exception {
        Holder h = aged(36_500_000);
        var future = fnol(h, 1000, List.of(), "tok_p");
        future.put("lossAt", Instant.now().plus(Duration.ofDays(1)).toString());
        h.api().post("/v1/claims", future, key("f1")).expect(400);
        var before = fnol(h, 1000, List.of(), "tok_p");
        before.put("lossAt", Instant.now().minus(Duration.ofDays(400)).toString());
        h.api().post("/v1/claims", before, key("f2")).expect(422);
        var bad = fnol(h, 1000, List.of(), "tok_p");
        bad.put("documents", List.of(Map.of("fileName", "x.exe", "contentType", "application/x-msdownload", "sizeBytes", 9)));
        h.api().post("/v1/claims", bad, key("f3")).expect(400);
        h.api().post("/v1/claims", fnol(h, 1000, List.of(), "tok_p"), Map.of()).expect(400);

        var body = fnol(h, 1000, List.of(), "tok_p");
        JsonNode first = h.api().post("/v1/claims", body, key("same")).expect(201).body();
        var replay = h.api().post("/v1/claims", body, key("same")).expect(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("claim").path("id").asText()).isEqualTo(first.path("claim").path("id").asText());

        String id = first.path("claim").path("id").asText();
        as(unique("stranger")).get("/v1/claims/" + id).expect(404);
        as(unique("stranger")).post("/v1/claims", fnol(h, 1000, List.of(), "tok_p"), key("s")).expect(404);
        // claiming "uploaded" before the bytes exist is refused
        String doc = first.path("uploads").get(0).path("documentId").asText();
        h.api().post("/v1/claims/" + id + "/documents/" + doc + "/uploaded", Map.of()).expect(422);
        assertThat(staff("ASSESSOR").get("/v1/claims/" + id).expect(200).body().path("fraudScore").isNull()).isTrue();
    }

    @Test
    void approvalChainEscalatesAndEnforcesFourEyes() throws Exception {
        Holder h = aged(36_500_000);
        String id = inAssessment(h, 8_000_000).path("id").asText();
        Api a1 = staff("ASSESSOR");
        Api a2 = staff("ASSESSOR");
        Api m = staff("MANAGER");

        a1.post("/v1/claims/" + id + "/approve", Map.of(), key("a")).expect(422); // not assessed yet
        a1.post("/v1/claims/" + id + "/assess", Map.of("assessedMinor", 7_000_000, "outcome", "APPROVE")).expect(200);
        // 70,000 rupees is over an assessor's 50,000 limit: the assessor who set the figure can't sign
        assertThat(a1.post("/v1/claims/" + id + "/approve", Map.of(), key("a1")).expect(403).text("code")).isEqualTo("four-eyes");
        JsonNode esc = a2.post("/v1/claims/" + id + "/approve", Map.of(), key("a2")).expect(202).body();
        assertThat(esc.path("pendingAuthority").asText()).isEqualTo("MANAGER");
        JsonNode ok = m.post("/v1/claims/" + id + "/approve", Map.of(), key("m")).expect(200).body();
        assertThat(ok.path("claim").path("status").asText()).isEqualTo("SETTLED");
        assertThat(ok.path("claim").path("approvedMinor").asLong()).isEqualTo(7_000_000);
        assertThat(ok.path("claim").path("approvals").size()).isEqualTo(2);

        var replay = m.post("/v1/claims/" + id + "/approve", Map.of(), key("m")).expect(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        m.post("/v1/claims/" + id + "/approve", Map.of(), key("m2")).expect(409);
        h.api().post("/v1/claims/" + id + "/approve", Map.of(), key("c")).expect(403);
        assertThat(gateway.approved("payout:settle:" + id)).isEqualTo(1);
    }

    @Test
    void aboveManagerLimitNeedsTwoManagersAndRacingSignoffsPayOnce() throws Exception {
        Holder h = aged(80_000_000);
        String id = inAssessment(h, 60_000_000).path("id").asText();
        staff("ASSESSOR").post("/v1/claims/" + id + "/assess", Map.of("assessedMinor", 60_000_000, "outcome", "APPROVE"))
                .expect(200);
        Api m1 = staff("MANAGER");
        assertThat(m1.post("/v1/claims/" + id + "/approve", Map.of(), key("x")).expect(202).text("pendingAuthority"))
                .isEqualTo("MANAGER");
        assertThat(m1.post("/v1/claims/" + id + "/approve", Map.of(), key("y")).expect(403).text("code"))
                .isEqualTo("already-signed");

        // six more managers sign at once: exactly one completes the dual control, the rest see a closed claim
        List<Api> others = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            others.add(staff("MANAGER"));
        }
        List<Integer> codes = Race.run(6, i -> others.get(i).post("/v1/claims/" + id + "/approve", Map.of(),
                key("race")).status());
        assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
        assertThat(codes).filteredOn(c -> c == 409).hasSize(5);
        assertThat(gateway.approved("payout:settle:" + id)).isEqualTo(1);
        assertThat(m1.get("/v1/claims/" + id).expect(200).text("status")).isEqualTo("SETTLED");
    }

    @Test
    void sharedPhoneAndBankLinkHoldersIntoARingThatGoesToInvestigation() throws Exception {
        String n = String.valueOf(10000 + (int) (Math.random() * 89999));
        String phone = "98" + n + "321";
        String bank = "SBIN" + n + "77";
        Holder a = aged(36_500_000);
        Holder b = aged(36_500_000);
        Holder c = aged(36_500_000);
        // the same phone, spelt differently, still links a and b
        JsonNode ca = upload(a, report(a, 500_000, List.of(party("PHONE", phone))));
        JsonNode cb = upload(b, report(b, 500_000, List.of(party("PHONE", "+91 " + phone.substring(0, 5) + "-" + phone.substring(5)),
                party("BANK_ACCOUNT", bank))));
        assertThat(ca.path("status").asText()).isEqualTo("SETTLED");
        // b is linked to one other holder: score 0.3 is at the STP ceiling, so b is not straight-through
        assertThat(cb.path("status").asText()).isEqualTo("ASSESSMENT");

        JsonNode cc = upload(c, report(c, 500_000, List.of(party("BANK_ACCOUNT", bank.toLowerCase()))));
        assertThat(cc.path("status").asText()).isEqualTo("INVESTIGATION");
        JsonNode staffView = staff("ASSESSOR").get("/v1/claims/" + cc.path("id").asText()).expect(200).body();
        assertThat(staffView.path("fraudScore").asDouble()).isGreaterThanOrEqualTo(0.6);
        assertThat(staffView.path("fraudReasons").toString()).contains("2 other holder");

        // only keyed hashes are stored
        assertThat(db.sql("select count(*) from claim_party where party_key_hash like ? or party_key_hash like ?")
                .params("%" + n + "%", "%" + phone + "%").query(Long.class).single()).isZero();
    }

    @Test
    void declinedPayoutLeavesTheClaimApprovedUntilARetryWithNewDetails() throws Exception {
        Holder h = aged(36_500_000);
        JsonNode fnol = h.api().post("/v1/claims", fnol(h, 400_000, List.of(), "tok_decline_payout"), key("d"))
                .expect(201).body();
        JsonNode c = upload(h, fnol);
        String id = c.path("id").asText();
        assertThat(c.path("status").asText()).isEqualTo("STP_APPROVED");
        Api m = staff("MANAGER");
        m.post("/v1/claims/" + id + "/settle", Map.of()).expect(402);
        JsonNode done = m.post("/v1/claims/" + id + "/settle", Map.of("payoutToken", "tok_payout_fixed")).expect(200).body();
        assertThat(done.path("status").asText()).isEqualTo("SETTLED");
        assertThat(gateway.approved("payout:settle:" + id)).isEqualTo(1);
    }

    @Test
    void rejectionIsFinal() throws Exception {
        Holder h = aged(36_500_000);
        String id = inAssessment(h, 3_000_000).path("id").asText();
        Api a = staff("ASSESSOR");
        a.post("/v1/claims/" + id + "/assess", Map.of("assessedMinor", 0, "outcome", "REJECT", "note", "pre-existing"))
                .expect(200);
        assertThat(a.get("/v1/claims/" + id).expect(200).text("status")).isEqualTo("REJECTED");
        a.post("/v1/claims/" + id + "/assess", Map.of("assessedMinor", 10, "outcome", "APPROVE")).expect(409);
        a.post("/v1/claims/" + id + "/approve", Map.of(), key("r")).expect(409);
        a.post("/v1/claims/" + id + "/assess", Map.of("assessedMinor", 1, "outcome", "APPROVE")).expect(409);
        assertThat(h.api().get("/v1/claims").expect(200).body().toString()).contains(id);
    }
}
