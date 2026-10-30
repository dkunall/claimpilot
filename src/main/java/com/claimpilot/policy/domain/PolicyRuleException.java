package com.claimpilot.policy.domain;

import com.claimpilot.shared.domain.DomainRuleException;

/** A request that breaks a policy rule; {@code code} is reported to the client. */
public class PolicyRuleException extends DomainRuleException {

    public PolicyRuleException(String code, String message) {
        super(code, message);
    }
}
