package com.claimpilot.policy.domain;

/** A request that breaks a policy rule; {@code code} is reported to the client. */
public class PolicyRuleException extends RuntimeException {

    private final String code;

    public PolicyRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
