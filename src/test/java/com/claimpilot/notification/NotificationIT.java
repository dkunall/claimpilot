package com.claimpilot.notification;

import com.claimpilot.outbox.application.EventEnvelope;
import com.claimpilot.outbox.application.Outbox;
import com.claimpilot.support.Api;
import com.claimpilot.support.Containers;
import com.claimpilot.support.ItBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Events reach the customer's inbox (Mailpit) through Kafka, once per event id. */
class NotificationIT extends ItBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ObjectMapper json;

    private record Bound(String email, String policyId, Api api) {
    }

    private Bound bind() {
        String sub = unique("notify");
        String email = sub.replace("@it.example.test", "@notify.example.test");
        Api c = as(sub);
        var q = quoteBody("health-shield", healthInputs(36_500_000, 30));
        q.put("holderEmail", email);
        String quote = c.post("/v1/quotes", q).expect(201).text("id");
        var bind = new LinkedHashMap<String, Object>();
        bind.put("paymentToken", "tok_visa_" + sub);
        String id = c.post("/v1/quotes/" + quote + "/bind", bind, key("bind-" + quote)).expect(201).text("policyId");
        return new Bound(email, id, c);
    }

    private List<String> subjects(String to) throws Exception {
        String q = URLEncoder.encode("to:\"" + to + "\"", StandardCharsets.UTF_8);
        var res = HTTP.send(HttpRequest.newBuilder(URI.create(Containers.mailpitApi() + "/api/v1/search?query=" + q))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        List<String> out = new ArrayList<>();
        for (JsonNode m : json.readTree(res.body()).path("messages")) {
            out.add(m.path("Subject").asText());
        }
        return out;
    }

    /** Polls until the inbox has at least {@code n} mails whose subject contains {@code part}, or 30 s pass. */
    private List<String> await(String to, String part, int n) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<String> hits;
        do {
            hits = subjects(to).stream().filter(s -> s.contains(part)).toList();
            if (hits.size() >= n) {
                return hits;
            }
            Thread.sleep(250);
        } while (System.nanoTime() < until);
        return hits;
    }

    private String text(String to, String part) throws Exception {
        String q = URLEncoder.encode("to:\"" + to + "\" subject:\"" + part + "\"", StandardCharsets.UTF_8);
        var res = HTTP.send(HttpRequest.newBuilder(URI.create(Containers.mailpitApi() + "/api/v1/search?query=" + q))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(res.body()).path("messages").get(0).path("ID").asText();
        var msg = HTTP.send(HttpRequest.newBuilder(URI.create(Containers.mailpitApi() + "/api/v1/message/" + id))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(msg.body()).path("Text").asText();
    }

    @Test
    void issuingAPolicyMailsTheHolderWithPremiumInRupees() throws Exception {
        Bound b = bind();
        List<String> hits = await(b.email(), "is active", 1);
        assertThat(hits).hasSize(1);
        String body = text(b.email(), "is active");
        assertThat(body).contains("Premium paid: INR ").contains("Asha Test");
    }

    @Test
    void theSameEventDeliveredTwiceSendsOneMailAndJunkRecordsAreSkipped() throws Exception {
        Bound b = bind();
        await(b.email(), "is active", 1);
        var event = new EventEnvelope(UUID.randomUUID(), "RenewalReminder", Instant.now(),
                Map.of("policyId", b.policyId(), "number", "x", "termEnd", "2030-03-01T06:30:00Z", "autopay", true));
        String payload = json.writeValueAsString(event);
        kafka.send(Outbox.POLICY_EVENTS, b.policyId(), "not an envelope").get();
        kafka.send(Outbox.POLICY_EVENTS, b.policyId(), payload).get();
        kafka.send(Outbox.POLICY_EVENTS, b.policyId(), payload).get();
        // a later distinct event proves the consumer got past both copies before we count
        var marker = new EventEnvelope(UUID.randomUUID(), "PolicyLapsed", Instant.now(),
                Map.of("policyId", b.policyId(), "number", "x", "coverEndedAt", "2030-03-16T18:30:00Z"));
        kafka.send(Outbox.POLICY_EVENTS, b.policyId(), json.writeValueAsString(marker)).get();
        assertThat(await(b.email(), "has lapsed", 1)).hasSize(1);

        assertThat(subjects(b.email()).stream().filter(s -> s.contains("renews soon")).count()).isEqualTo(1);
        assertThat(text(b.email(), "renews soon")).contains("1 Mar 2030");
        assertThat(text(b.email(), "has lapsed")).contains("17 Mar 2030"); // 18:30Z is midnight IST
        assertThat(db.sql("select count(*) from processed_event where consumer = 'notifications' and event_id = ?")
                .param(event.eventId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void eventsCarryIdsNotContactDetails() {
        Bound b = bind();
        String payload = db.sql("select payload::text from outbox where msg_key = ? and type = 'PolicyIssued'")
                .param(b.policyId()).query(String.class).single();
        assertThat(payload).doesNotContain(b.email()).doesNotContain("Asha");
    }
}
