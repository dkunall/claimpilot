package com.claimpilot.claim.domain;

import com.claimpilot.fraud.domain.FraudScore;
import com.claimpilot.product.domain.ClaimRules;
import com.claimpilot.product.domain.Coverage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Straight-through processing rules. Pure: same facts and rules, same answer. */
public final class Triage {

    private Triage() {
    }

    public record Facts(long claimedMinor, Coverage coverage, Instant lossAt, Instant reportedAt,
                        Instant continuousCoverSince, long priorClaimsInWindow, long documentsUploaded,
                        FraudScore fraud) {
    }

    public static TriageResult decide(Facts f, ClaimRules r) {
        long reserve = Math.max(0, Math.min(f.claimedMinor(), f.coverage().limitMinor()) - f.coverage().deductibleMinor());
        List<String> failed = new ArrayList<>();
        if (reserve == 0) {
            failed.add("nothing payable after the deductible of " + f.coverage().deductibleMinor());
        }
        if (f.claimedMinor() > r.stpLimitMinor()) {
            failed.add("claimed " + f.claimedMinor() + " is over the straight-through limit " + r.stpLimitMinor());
        }
        if (f.claimedMinor() > f.coverage().limitMinor()) {
            failed.add("claimed amount is over the coverage limit " + f.coverage().limitMinor());
        }
        long ageDays = Duration.between(f.continuousCoverSince(), f.lossAt()).toDays();
        if (ageDays < r.minPolicyAgeDays()) {
            failed.add("loss " + ageDays + " days into cover, inside the " + r.minPolicyAgeDays() + "-day waiting period");
        }
        long delayDays = Duration.between(f.lossAt(), f.reportedAt()).toDays();
        if (delayDays > r.maxReportDelayDays()) {
            failed.add("reported " + delayDays + " days after the loss (limit " + r.maxReportDelayDays() + ")");
        }
        if (f.priorClaimsInWindow() > 0) {
            failed.add(f.priorClaimsInWindow() + " other claim(s) on this policy in the last "
                    + r.priorClaimWindowDays() + " days");
        }
        if (f.documentsUploaded() < r.documentsRequired()) {
            failed.add(f.documentsUploaded() + " of " + r.documentsRequired() + " required documents uploaded");
        }
        boolean investigate = f.fraud().score() >= r.investigateFraudScore();
        if (f.fraud().score() >= r.stpMaxFraudScore()) {
            failed.add(String.format("fraud score %.2f is at or over %.2f", f.fraud().score(), r.stpMaxFraudScore()));
        }
        boolean stp = failed.isEmpty();
        return new TriageResult(stp, investigate, reserve, stp ? List.of("all straight-through rules passed") : failed);
    }
}
