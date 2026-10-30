package com.claimpilot.policy;

import com.claimpilot.support.Api;
import com.claimpilot.support.ItBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BitemporalIT extends ItBase {

    @Test
    void bindIssuesOnePolicyAndReplaysTheSameAnswer() {
        String sub = unique("cust");
        Api c = as(sub);
        JsonNode quote = c.post("/v1/quotes", quoteBody("health-shield", healthInputs(50_000_000, 30))).expect(201).body();
        long total = quote.path("premium").path("totalMinor").asLong();
        String id = quote.path("id").asText();
        var bind = Map.of("paymentToken", "tok_visa_1", "autopay", true);

        JsonNode first = c.post("/v1/quotes/" + id + "/bind", bind, key("k-" + id)).expect(201).body();
        var replay = c.post("/v1/quotes/" + id + "/bind", bind, key("k-" + id)).expect(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body().path("policyId")).isEqualTo(first.path("policyId"));
        // a different key on a bound quote is refused, and nobody pays twice
        c.post("/v1/quotes/" + id + "/bind", bind, key("other-" + id)).expect(409);
        // same key, different body
        c.post("/v1/quotes/" + id + "/bind", Map.of("paymentToken", "tok_visa_2", "autopay", false), key("k-" + id))
                .expect(422);

        assertThat(first.path("annualPremiumMinor").asLong()).isEqualTo(total);
        assertThat(first.path("number").asText()).startsWith("CP-H-");
        assertThat(first.path("receiptNo").asText()).startsWith("RCPT-");
        String policyId = first.path("policyId").asText();
        Long paid = db.sql("select sum(amount_minor) from payment where policy_id = ?::uuid and status = 'SUCCEEDED'")
                .param(policyId).query(Long.class).single();
        assertThat(paid).isEqualTo(total);

        JsonNode policy = c.get("/v1/policies/" + policyId).expect(200).body();
        assertThat(policy.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(policy.path("cover").path("annualPremiumMinor").asLong()).isEqualTo(total);
        // someone else can't see it, and it looks missing rather than forbidden
        as(unique("stranger")).get("/v1/policies/" + policyId).expect(404);
    }

    @Test
    void bindRequiresAnIdempotencyKeyAndADeclineChangesNothing() {
        Api c = as(unique("cust"));
        String q = c.post("/v1/quotes", quoteBody("health-shield", healthInputs(30_000_000, 40))).expect(201).text("id");
        c.post("/v1/quotes/" + q + "/bind", Map.of("paymentToken", "tok_visa"), Map.of()).expect(400);
        var declined = c.post("/v1/quotes/" + q + "/bind", Map.of("paymentToken", "tok_decline_card"), key("d-" + q));
        assertThat(declined.status()).isEqualTo(402);
        assertThat(declined.text("code")).isEqualTo("payment-declined");
        assertThat(db.sql("select status from quote where id = ?::uuid").param(q).query(String.class).single())
                .isEqualTo("QUOTED");
        // a failed attempt releases its key, so the customer can retry it with a working card
        c.post("/v1/quotes/" + q + "/bind", Map.of("paymentToken", "tok_visa"), key("d-" + q)).expect(201);
    }

    @Test
    void endorsementRewritesTheFutureButKeepsWhatWeBelievedBefore() {
        Holder h = healthHolder(36_500_000, null);
        String id = h.policyId();
        JsonNode before = h.api().get("/v1/policies/" + id).expect(200).body();
        Instant start = Instant.parse(before.path("termStart").asText());
        long oldPremium = before.path("cover").path("annualPremiumMinor").asLong();
        Instant recordedBefore = Instant.now();
        Instant effective = start.plus(Duration.ofDays(100));

        var change = Map.of("effectiveFrom", effective.toString(), "sumInsuredMinor", 73_000_000);
        var r = h.api().post("/v1/policies/" + id + "/endorsements", change, key("e1"));
        r.expect(201);
        long delta = r.body().path("premiumDeltaMinor").asLong();
        assertThat(delta).isPositive();
        assertThat(r.body().path("receiptNo").asText()).startsWith("RCPT-");
        assertThat(r.body().path("current")).hasSize(2);
        h.api().post("/v1/policies/" + id + "/endorsements", change, key("e1")).expect(200);

        Instant later = effective.plus(Duration.ofDays(10));
        // now: after the effective date we have the new sum insured
        JsonNode now = h.api().get("/v1/policies/" + id + "?validAt=" + later).expect(200).body();
        assertThat(now.at("/cover/coverage/inputs/sumInsuredMinor").asLong()).isEqualTo(73_000_000);
        long newPremium = now.at("/cover/annualPremiumMinor").asLong();
        assertThat(newPremium).isGreaterThan(oldPremium);
        // what we believed before the endorsement about the same date
        JsonNode then = h.api().get("/v1/policies/" + id + "?validAt=" + later + "&asOf=" + recordedBefore)
                .expect(200).body();
        assertThat(then.at("/cover/coverage/inputs/sumInsuredMinor").asLong()).isEqualTo(36_500_000);
        // before the effective date nothing changed
        JsonNode early = h.api().get("/v1/policies/" + id + "?validAt=" + start.plus(Duration.ofDays(5))).expect(200).body();
        assertThat(early.at("/cover/coverage/inputs/sumInsuredMinor").asLong()).isEqualTo(36_500_000);
        // the charge is exactly the pro-rated difference for the 265 days left
        long expected = Math.round((newPremium - oldPremium) * 265 / 365.0);
        assertThat(Math.abs(delta - expected)).isLessThanOrEqualTo(1);

        JsonNode history = h.api().get("/v1/policies/" + id + "/history").expect(200).body();
        assertThat(history.path("versions")).hasSize(3);
        // outside cover
        h.api().get("/v1/policies/" + id + "?validAt=" + start.minus(Duration.ofDays(1))).expect(404);
        h.api().post("/v1/policies/" + id + "/endorsements",
                Map.of("effectiveFrom", start.plus(Duration.ofDays(400)).toString(), "sumInsuredMinor", 1_000_000),
                key("e-out")).expect(422);
    }

    @Test
    void ifMatchRejectsAStaleWriter() {
        Holder h = healthHolder(30_000_000, null);
        String id = h.policyId();
        var body = Map.of("sumInsuredMinor", 40_000_000);
        h.api().post("/v1/policies/" + id + "/endorsements", body, Map.of("Idempotency-Key", "a", "If-Match", "0"))
                .expect(201);
        h.api().post("/v1/policies/" + id + "/endorsements", Map.of("sumInsuredMinor", 45_000_000),
                Map.of("Idempotency-Key", "b", "If-Match", "0")).expect(412);
    }

    @Test
    void cancellationRefundsTheUnusedPremium() {
        Holder h = healthHolder(30_000_000, null);
        String id = h.policyId();
        var r = h.api().post("/v1/policies/" + id + "/endorsements",
                Map.of("cancel", true, "effectiveFrom", Instant.now().plus(Duration.ofDays(1)).toString()), key("c"))
                .expect(201).body();
        assertThat(r.path("premiumDeltaMinor").asLong()).isNegative();
        assertThat(h.api().get("/v1/policies/" + id).expect(200).text("status")).isEqualTo("CANCELLED");
        Long refunded = db.sql("select amount_minor from payment where policy_id = ?::uuid and kind = 'REFUND'")
                .param(id).query(Long.class).single();
        assertThat(refunded).isEqualTo(r.path("premiumDeltaMinor").asLong());
        h.api().post("/v1/policies/" + id + "/endorsements", Map.of("sumInsuredMinor", 40_000_000), key("after"))
                .expect(409);
    }

    @Test
    void scheduleAndReceiptAreRealPdfs() throws Exception {
        Holder h = healthHolder(30_000_000, null);
        JsonNode link = h.api().get("/v1/policies/" + h.policyId() + "/schedule").expect(200).body();
        var http = HttpClient.newHttpClient();
        var pdf = http.send(HttpRequest.newBuilder(URI.create(link.path("url").asText())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 5)).isEqualTo("%PDF-");

        String paymentId = h.policy().path("paymentId").asText();
        var receipt = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/payments/"
                        + paymentId + "/receipt")).header("Authorization", "Bearer " + com.claimpilot.support.TestJwt.user(h.subject()))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(new String(receipt.body(), 0, 5)).isEqualTo("%PDF-");
        as(unique("stranger")).get("/v1/payments/" + paymentId + "/receipt").expect(404);
    }
}
