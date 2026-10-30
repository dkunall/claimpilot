package com.claimpilot.support;

import com.claimpilot.payment.adapter.FakePaymentGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole app on a random port against real Postgres, Kafka, Mailpit and an S3-compatible store. Every IT
 * class shares this configuration, so Spring caches one context for the run. Tests make their own customers,
 * so they never see each other's data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ItBase.TestBeans.class)
public abstract class ItBase {

    public static final String MANAGER = "manager@it.example.test";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient db;

    @Autowired
    protected FakePaymentGateway gateway;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        var pg = Containers.postgres();
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", () -> Containers.DB_PASSWORD);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        r.add("spring.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        r.add("spring.mail.host", () -> Containers.mailpit().getHost());
        r.add("spring.mail.port", () -> Containers.mailpit().getMappedPort(1025));
        r.add("claimpilot.storage.endpoint", Containers::s3Endpoint);
        r.add("claimpilot.storage.public-endpoint", Containers::s3Endpoint);
        r.add("claimpilot.storage.access-key", () -> Containers.S3_ACCESS_KEY);
        r.add("claimpilot.storage.secret-key", () -> Containers.S3_SECRET_KEY);
        r.add("claimpilot.security.party-hmac-key", () -> "it-only-hmac-key-" + UUID.randomUUID());
        r.add("claimpilot.security.bootstrap-managers", () -> MANAGER);
        r.add("claimpilot.quotes.per-minute", () -> "100000");
        r.add("claimpilot.quotes.burst", () -> "100000");
    }

    protected Api anon;
    protected Api manager;

    @BeforeEach
    void clients() {
        anon = Api.at(port);
        manager = as(MANAGER);
    }

    protected Api as(String subject) {
        return Api.at(port).as(TestJwt.user(subject));
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@it.example.test";
    }

    protected static Map<String, String> key(String k) {
        return Map.of("Idempotency-Key", k);
    }

    /** A staff member with the given role, as an API client. */
    protected Api staff(String role) {
        String sub = unique(role.toLowerCase());
        manager.put("/v1/staff/" + sub, Map.of("role", role)).expect(204);
        return as(sub);
    }

    public static Map<String, Object> healthInputs(long sumInsured, int age) {
        return Map.of("sumInsuredMinor", sumInsured,
                "numeric", Map.of("age", age, "members", 1),
                "categorical", Map.of("region", "METRO"),
                "riders", List.of());
    }

    public static Map<String, Object> quoteBody(String product, Map<String, Object> inputs) {
        var b = new LinkedHashMap<String, Object>();
        b.put("productId", product);
        b.put("holderName", "Asha Test");
        b.put("holderEmail", "asha@example.test");
        b.put("inputs", inputs);
        return b;
    }

    /** A customer holding an active health policy; returned as (client, policy json). */
    public record Holder(String subject, Api api, JsonNode policy) {
        public String policyId() {
            return policy.path("policyId").asText();
        }
    }

    protected Holder healthHolder(long sumInsured, Instant start) {
        String sub = unique("cust");
        Api c = as(sub);
        String quote = c.post("/v1/quotes", quoteBody("health-shield", healthInputs(sumInsured, 30))).expect(201).text("id");
        var bind = new LinkedHashMap<String, Object>();
        bind.put("paymentToken", "tok_visa_" + sub);
        bind.put("autopay", true);
        if (start != null) {
            bind.put("startAt", start.toString());
        }
        JsonNode p = c.post("/v1/quotes/" + quote + "/bind", bind, key("bind-" + quote)).expect(201).body();
        return new Holder(sub, c, p);
    }
}
