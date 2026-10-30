package com.claimpilot.policy.adapter;

import com.claimpilot.idempotency.adapter.IdempotentResponses;
import com.claimpilot.policy.application.PolicyService;
import com.claimpilot.policy.application.PolicyService.EndorseCommand;
import com.claimpilot.policy.application.PolicyViews.EndorsementResult;
import com.claimpilot.policy.application.PolicyViews.History;
import com.claimpilot.policy.application.PolicyViews.PolicyView;
import com.claimpilot.policy.domain.EndorsementChange;
import com.claimpilot.shared.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
class PolicyController {

    private final PolicyService policies;

    PolicyController(PolicyService policies) {
        this.policies = policies;
    }

    record EndorseRequest(Instant effectiveFrom, Long sumInsuredMinor, Set<String> addRiders, Set<String> removeRiders,
                          Map<String, BigDecimal> numeric, Map<String, String> categorical, boolean cancel,
                          String paymentToken) {
    }

    @GetMapping("/v1/policies")
    List<PolicyView> mine(Caller caller) {
        return policies.mine(caller);
    }

    /** Cover valid at {@code validAt} (default now) as recorded at {@code asOf} (default now). */
    @GetMapping("/v1/policies/{id}")
    PolicyView get(Caller caller, @PathVariable UUID id, @RequestParam(required = false) Instant validAt,
                   @RequestParam(required = false) Instant asOf) {
        return policies.get(caller, id, validAt, asOf);
    }

    @GetMapping("/v1/policies/{id}/history")
    History history(Caller caller, @PathVariable UUID id) {
        return policies.history(caller, id);
    }

    @PostMapping("/v1/policies/{id}/endorsements")
    ResponseEntity<EndorsementResult> endorse(Caller caller, @PathVariable UUID id,
                                              @RequestHeader(name = IdempotentResponses.KEY_HEADER, required = false) String key,
                                              @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                              @RequestBody EndorseRequest r) {
        var change = new EndorsementChange(r.sumInsuredMinor(), r.addRiders(), r.removeRiders(), r.numeric(),
                r.categorical(), r.cancel());
        var cmd = new EndorseCommand(r.effectiveFrom(), change, r.paymentToken());
        return IdempotentResponses.of(policies.endorse(caller, id, key, parseIfMatch(ifMatch), cmd), HttpStatus.CREATED);
    }

    private static Long parseIfMatch(String h) {
        if (h == null || h.isBlank()) {
            return null;
        }
        String v = h.trim().replace("W/", "").replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be the policy lockVersion");
        }
    }
}
