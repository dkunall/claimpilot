package com.claimpilot.product.domain;

/** One thing the policy pays for, with its per-claim limit and the deductible the holder bears first. */
public record Coverage(String code, String name, long limitMinor, long deductibleMinor) {

    public Coverage {
        Checks.code(code, "coverage code");
        Checks.text(name, "coverage name");
        if (limitMinor <= 0 || deductibleMinor < 0 || deductibleMinor >= limitMinor) {
            throw new InvalidProductException("coverage " + code + ": need 0 <= deductible < limit");
        }
    }

    /** The sum insured caps every limit, so a cheaper policy can't buy a bigger hospital cover by accident. */
    public Coverage cappedAt(long sumInsuredMinor) {
        return limitMinor <= sumInsuredMinor ? this
                : new Coverage(code, name, sumInsuredMinor, Math.min(deductibleMinor, sumInsuredMinor - 1));
    }
}
