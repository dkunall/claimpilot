package com.claimpilot.shared.security;

import com.claimpilot.shared.error.ApiException;

/** Who is calling. Passed explicitly into application services so authorisation is visible in the code. */
public record Caller(String subject, Role role) {

    public static Caller system(String job) {
        return new Caller("system:" + job, Role.MANAGER);
    }

    public boolean is(Role r) {
        return role == r;
    }

    public void require(Role... allowed) {
        for (Role r : allowed) {
            if (role == r) {
                return;
            }
        }
        throw ApiException.forbidden("forbidden", "This needs one of the roles " + java.util.Arrays.toString(allowed));
    }

    /**
     * Customers see their own records, agents the ones they sold, assessors and managers everything. A record
     * the caller can't see is reported as missing rather than forbidden, so ids can't be probed.
     */
    public boolean canSee(String holderSubject, String agentSubject) {
        return switch (role) {
            case ASSESSOR, MANAGER -> true;
            case AGENT -> subject.equals(agentSubject) || subject.equals(holderSubject);
            case CUSTOMER -> subject.equals(holderSubject);
        };
    }
}
