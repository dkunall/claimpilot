package com.claimpilot.renewal;

import com.claimpilot.renewal.application.RenewalService;
import com.claimpilot.support.Api;
import com.claimpilot.support.ItBase;
import com.claimpilot.support.Race;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The renewal job end to end. Every test uses its own business date far in the future and moves only its own
 * policies' terms to end on that date, so the job (which looks at every policy) only finds this test's work.
 */
class RenewalIT extends ItBase {

    /** Business dates 1000+ days apart, so renewed terms of one test never fall due in another's window. */
    private static LocalDate day(int slot) {
        return LocalDate.now(RenewalService.BUSINESS_ZONE).plusDays(3000L + slot * 1000L);
    }

    private record P(String subject, Api api, UUID id, String email) {
    }

    private P policy(String token) {
        String sub = unique("renew");
        String email = sub.replace("@it.example.test", "@renew.example.test");
        Api c = as(sub);
        var q = quoteBody("health-shield", healthInputs(36_500_000, 30));
        q.put("holderEmail", email);
        String quote = c.post("/v1/quotes", q).expect(201).text("id");
        var bind = new LinkedHashMap<String, Object>();
        bind.put("paymentToken", "tok_visa_" + sub);
        bind.put("autopay", true);
        UUID id = UUID.fromString(c.post("/v1/quotes/" + quote + "/bind", bind, key("bind-" + quote)).expect(201)
                .text("policyId"));
        if (token == null || !token.startsWith("tok_visa")) {
            db.sql("update policy set autopay_token = ? where id = ?").params(token, id).update();
        }
        return new P(sub, c, id, email);
    }

    /** Test fixture: shifts the whole policy in time so its current term ends at noon IST on {@code date}. */
    private void endsOn(P p, LocalDate date) {
        Instant target = date.atTime(12, 0).atZone(RenewalService.BUSINESS_ZONE).toInstant();
        Instant end = db.sql("select term_end from policy where id = ?").param(p.id()).query(Timestamp.class)
                .single().toInstant();
        String shift = ChronoUnit.MICROS.between(end, target) + " microseconds";
        db.sql("""
                update policy set first_start = first_start + cast(? as interval), term_start = term_start + cast(? as interval),
                    term_end = term_end + cast(? as interval) where id = ?""").params(shift, shift, shift, p.id()).update();
        db.sql("""
                update policy_version set valid = tstzrange(lower(valid) + cast(? as interval), upper(valid) + cast(? as interval), '[)')
                where policy_id = ?""").params(shift, shift, p.id()).update();
    }

    private Instant termEnd(P p) {
        return db.sql("select term_end from policy where id = ?").param(p.id()).query(Timestamp.class).single()
                .toInstant();
    }

    private String status(P p) {
        return db.sql("select status from policy where id = ?").param(p.id()).query(String.class).single();
    }

    private long renewalCharges(P p) {
        return gateway.approved("charge:renewal:" + p.id() + ":");
    }

    private Api.Response run(LocalDate date) {
        return manager.post("/v1/renewals/run?date=" + date, null);
    }

    @Test
    void renewsDueTermsOnceSendsGraceForUnpaidAndRemindsUpcoming() {
        LocalDate d = day(0);
        List<P> paying = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            P p = policy("tok_visa");
            endsOn(p, d);
            paying.add(p);
        }
        P declined = policy("tok_decline_renewal");
        endsOn(declined, d);
        P noMandate = policy(null);
        endsOn(noMandate, d);
        P upcoming = policy("tok_visa");
        endsOn(upcoming, d.plusDays(10));
        Instant oldEnd = termEnd(paying.getFirst());

        JsonNode r = run(d).expect(200).body();
        assertThat(r.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(r.path("renewed").asLong()).isEqualTo(12);
        assertThat(r.path("paymentDue").asLong()).isEqualTo(2);
        assertThat(r.path("reminders").asLong()).isEqualTo(1);
        assertThat(r.path("replayed").asBoolean()).isFalse();

        for (P p : paying) {
            assertThat(status(p)).isEqualTo("ACTIVE");
            assertThat(termEnd(p)).isEqualTo(oldEnd.plus(RenewalService.TERM));
            assertThat(renewalCharges(p)).isEqualTo(1);
        }
        assertThat(status(declined)).isEqualTo("GRACE");
        assertThat(status(noMandate)).isEqualTo("GRACE");
        assertThat(status(upcoming)).isEqualTo("ACTIVE");
        assertThat(termEnd(upcoming)).isAfter(oldEnd); // not due yet: untouched

        // the renewed term is a new bitemporal row, readable at a date inside it
        P first = paying.getFirst();
        JsonNode cover = first.api().get("/v1/policies/" + first.id() + "?validAt=" + oldEnd.plus(30, ChronoUnit.DAYS))
                .expect(200).body();
        assertThat(cover.toString()).contains("renewal");

        // same date again: the completed run is returned, nothing is charged twice
        JsonNode again = run(d).expect(200).body();
        assertThat(again.path("replayed").asBoolean()).isTrue();
        assertThat(again.path("jobExecutionId").asLong()).isEqualTo(r.path("jobExecutionId").asLong());
        for (P p : paying) {
            assertThat(renewalCharges(p)).isEqualTo(1);
        }
        assertThat(db.sql("select count(*) from renewal_reminder where policy_id = ?").param(upcoming.id())
                .query(Long.class).single()).isEqualTo(1);

        // customers can't run the job
        first.api().post("/v1/renewals/run?date=" + d, null).expect(403);
    }

    @Test
    void graceKeepsCoverUntilPaidAndAnUnpaidPolicyLapsesWithHistoryIntact() {
        LocalDate d = day(1);
        P payer = policy("tok_decline_card_expired");
        P lapser = policy("tok_decline_card_expired");
        endsOn(payer, d);
        endsOn(lapser, d);
        Instant periodStart = termEnd(payer);
        run(d).expect(200);
        assertThat(status(payer)).isEqualTo("GRACE");

        // a declined card leaves it in grace; a stranger can't see it; claims staff can't take money
        payer.api().post("/v1/policies/" + payer.id() + "/payments", java.util.Map.of("paymentToken", "tok_decline_x"),
                key("pay-declined")).expect(402);
        as(unique("stranger")).post("/v1/policies/" + payer.id() + "/payments",
                java.util.Map.of("paymentToken", "tok_visa_s"), key("pay-s")).expect(404);
        staff("ASSESSOR").post("/v1/policies/" + payer.id() + "/payments",
                java.util.Map.of("paymentToken", "tok_visa_s"), key("pay-a")).expect(403);
        assertThat(status(payer)).isEqualTo("GRACE");

        var paid = payer.api().post("/v1/policies/" + payer.id() + "/payments",
                java.util.Map.of("paymentToken", "tok_visa_paid"), key("pay-1")).expect(200);
        assertThat(paid.text("status")).isEqualTo("ACTIVE");
        assertThat(paid.text("receiptNo")).isNotBlank();
        var replay = payer.api().post("/v1/policies/" + payer.id() + "/payments",
                java.util.Map.of("paymentToken", "tok_visa_paid"), key("pay-1")).expect(200);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        payer.api().post("/v1/policies/" + payer.id() + "/payments",
                java.util.Map.of("paymentToken", "tok_visa_paid"), key("pay-2")).expect(409);
        assertThat(gateway.approved("charge:grace:" + payer.id() + ":")).isEqualTo(1);
        JsonNode history = payer.api().get("/v1/policies/" + payer.id() + "/renewals").expect(200).body();
        assertThat(history.get(0).path("status").asText()).isEqualTo("RENEWED");

        // 16 days later grace is over for the one nobody paid
        Instant beforeLapse = Instant.now();
        JsonNode later = run(d.plusDays(16)).expect(200).body();
        assertThat(later.path("lapsed").asLong()).isEqualTo(1);
        assertThat(status(lapser)).isEqualTo("LAPSED");
        assertThat(status(payer)).isEqualTo("ACTIVE");

        Instant inGrace = periodStart.plus(5, ChronoUnit.DAYS);
        Instant afterGrace = periodStart.plus(20, ChronoUnit.DAYS);
        String base = "/v1/policies/" + lapser.id() + "?validAt=";
        lapser.api().get(base + inGrace).expect(200);
        lapser.api().get(base + afterGrace).expect(404);
        // what we believed before the lapse is still answerable
        lapser.api().get(base + afterGrace + "&asOf=" + beforeLapse).expect(200);
        payer.api().get("/v1/policies/" + payer.id() + "?validAt=" + afterGrace).expect(200);

        lapser.api().post("/v1/policies/" + lapser.id() + "/payments", java.util.Map.of("paymentToken", "tok_visa_late"),
                key("pay-late")).expect(409);
    }

    @Test
    void racingRunsOfOneDateRenewEachPolicyOnce() {
        LocalDate d = day(2);
        List<P> ps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            P p = policy("tok_visa");
            endsOn(p, d);
            ps.add(p);
        }
        List<Integer> codes = Race.run(4, i -> run(d).status());
        assertThat(new HashSet<>(codes)).isSubsetOf(200, 409);
        assertThat(codes).contains(200);
        for (P p : ps) {
            assertThat(renewalCharges(p)).isEqualTo(1);
            assertThat(db.sql("select count(*) from renewal where policy_id = ?").param(p.id()).query(Long.class)
                    .single()).isEqualTo(1);
            assertThat(db.sql("select count(*) from policy_version where policy_id = ? and upper_inf(recorded)")
                    .param(p.id()).query(Long.class).single()).isEqualTo(2);
        }
    }

    @Test
    void aRunThatFailsMidwayRestartsWhereItStoppedAndChargesOnce() {
        LocalDate d = day(3);
        List<P> ps = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            P p = policy("tok_visa");
            endsOn(p, d);
            ps.add(p);
        }
        P broken = ps.get(80);
        db.sql("update policy set autopay_token = 'tok_outage' where id = ?").param(broken.id()).update();

        JsonNode failed = run(d).expect(500).body();
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        long firstRun = failed.path("renewed").asLong();
        assertThat(firstRun).isBetween(1L, 119L);
        assertThat(status(broken)).isEqualTo("ACTIVE");

        // the gateway is back
        db.sql("update policy set autopay_token = 'tok_visa_back' where id = ?").param(broken.id()).update();
        JsonNode restarted = run(d).expect(200).body();
        assertThat(restarted.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(restarted.path("jobExecutionId").asLong()).isNotEqualTo(failed.path("jobExecutionId").asLong());
        assertThat(firstRun + restarted.path("renewed").asLong()).isEqualTo(120);
        for (P p : ps) {
            assertThat(renewalCharges(p)).isEqualTo(1);
            assertThat(status(p)).isEqualTo("ACTIVE");
        }
    }
}
