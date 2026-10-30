package com.claimpilot.security;

import com.claimpilot.support.Api;
import com.claimpilot.support.ItBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Who can do what over HTTP: 401 without a valid token, 403 for the wrong role, 404 for someone else's data. */
class SecurityIT extends ItBase {

    @Test
    void noOrBadTokenIs401AndUnknownPathsAreClosed() {
        anon.get("/v1/me").expect(401);
        anon.get("/v1/products").expect(401);
        Api.at(port).as("not-a-jwt").get("/v1/me").expect(401);
        assertThat(anon.get("/actuator/env").status()).isIn(401, 403);
        assertThat(manager.get("/actuator/env").status()).isEqualTo(403);
        anon.get("/actuator/health").expect(200);
    }

    @Test
    void rolesComeFromTheStaffTableAndOnlyManagersChangeThem() {
        String sub = unique("customer");
        Api customer = as(sub);
        assertThat(customer.get("/v1/me").expect(200).text("role")).isEqualTo("CUSTOMER");
        customer.get("/v1/staff").expect(403);
        customer.put("/v1/staff/" + sub, Map.of("role", "MANAGER")).expect(403);

        Api assessor = staff("ASSESSOR");
        assessor.put("/v1/staff/" + sub, Map.of("role", "MANAGER")).expect(403);
        assessor.post("/v1/renewals/run?date=2031-01-01", null).expect(403);

        manager.put("/v1/staff/" + sub, Map.of("role", "AGENT")).expect(204);
        assertThat(customer.get("/v1/me").expect(200).text("role")).isEqualTo("AGENT");
        manager.delete("/v1/staff/" + sub).expect(204);
        assertThat(customer.get("/v1/me").expect(200).text("role")).isEqualTo("CUSTOMER");
        manager.delete("/v1/staff/" + MANAGER).expect(409);
    }

    @Test
    void customersOnlySeeTheirOwnPoliciesAndClaimsStaffSeeAll() {
        Holder h = healthHolder(36_500_000, java.time.Instant.now());
        as(unique("stranger")).get("/v1/policies/" + h.policyId()).expect(404);
        as(unique("stranger")).get("/v1/policies/" + h.policyId() + "/history").expect(404);
        h.api().get("/v1/policies/" + h.policyId()).expect(200);
        staff("ASSESSOR").get("/v1/policies/" + h.policyId()).expect(200);
    }

    @Test
    void prometheusExposesBusinessMetricsWithoutAuth() {
        Holder h = healthHolder(36_500_000, java.time.Instant.now());
        h.api().get("/v1/policies/" + h.policyId()).expect(200);
        String text = anon.get("/actuator/prometheus").expect(200).body().asText();
        assertThat(text).contains("claimpilot_policy_read_seconds").contains("claimpilot_outbox_published");
        assertThat(text).doesNotContain("@it.example.test");
    }
}
