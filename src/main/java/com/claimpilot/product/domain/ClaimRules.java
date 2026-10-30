package com.claimpilot.product.domain;

/**
 * How claims on this product are triaged. A claim passes straight through only when it is at or under
 * {@code stpLimitMinor}, the policy is at least {@code minPolicyAgeDays} old at the loss date, it is reported
 * within {@code maxReportDelayDays}, the policy had no other claim in the last {@code priorClaimWindowDays},
 * and its fraud score is below {@code stpMaxFraudScore}. A score at or above {@code investigateFraudScore}
 * sends it to investigation.
 */
public record ClaimRules(
        long stpLimitMinor,
        int minPolicyAgeDays,
        int maxReportDelayDays,
        int priorClaimWindowDays,
        int documentsRequired,
        double stpMaxFraudScore,
        double investigateFraudScore) {

    public ClaimRules {
        if (stpLimitMinor < 0 || minPolicyAgeDays < 0 || maxReportDelayDays < 1 || priorClaimWindowDays < 0
                || documentsRequired < 0 || documentsRequired > 10) {
            throw new InvalidProductException("claim rules: limits and day counts must be non-negative, documents 0-10");
        }
        if (!(stpMaxFraudScore >= 0 && stpMaxFraudScore <= investigateFraudScore && investigateFraudScore <= 1)) {
            throw new InvalidProductException("claim rules: need 0 <= stpMaxFraudScore <= investigateFraudScore <= 1");
        }
    }
}
