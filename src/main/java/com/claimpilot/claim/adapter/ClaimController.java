package com.claimpilot.claim.adapter;

import com.claimpilot.claim.application.ClaimService;
import com.claimpilot.claim.application.ClaimViews.ApproveResult;
import com.claimpilot.claim.application.ClaimViews.AssessCommand;
import com.claimpilot.claim.application.ClaimViews.ClaimView;
import com.claimpilot.claim.application.ClaimViews.FnolCommand;
import com.claimpilot.claim.application.ClaimViews.FnolResult;
import com.claimpilot.claim.application.ClaimViews.Upload;
import com.claimpilot.claim.domain.ClaimStatus;
import com.claimpilot.idempotency.adapter.IdempotentResponses;
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

import java.util.List;
import java.util.UUID;

@RestController
class ClaimController {

    private final ClaimService claims;

    ClaimController(ClaimService claims) {
        this.claims = claims;
    }

    record SettleRequest(String payoutToken) {
    }

    @PostMapping("/v1/claims")
    ResponseEntity<FnolResult> report(Caller caller,
                                      @RequestHeader(name = IdempotentResponses.KEY_HEADER, required = false) String key,
                                      @RequestBody FnolCommand cmd) {
        return IdempotentResponses.of(claims.report(caller, key, cmd), HttpStatus.CREATED);
    }

    @GetMapping("/v1/claims")
    List<ClaimView> list(Caller caller, @RequestParam(required = false) ClaimStatus status) {
        return claims.list(caller, status);
    }

    @GetMapping("/v1/claims/{id}")
    ClaimView get(Caller caller, @PathVariable UUID id) {
        return claims.get(caller, id);
    }

    @PostMapping("/v1/claims/{id}/documents/{documentId}/upload-url")
    Upload uploadUrl(Caller caller, @PathVariable UUID id, @PathVariable UUID documentId) {
        return claims.uploadUrl(caller, id, documentId);
    }

    @PostMapping("/v1/claims/{id}/documents/{documentId}/uploaded")
    ClaimView uploaded(Caller caller, @PathVariable UUID id, @PathVariable UUID documentId) {
        return claims.documentUploaded(caller, id, documentId);
    }

    @PostMapping("/v1/claims/{id}/triage")
    ClaimView triage(Caller caller, @PathVariable UUID id) {
        return claims.triage(caller, id);
    }

    @PostMapping("/v1/claims/{id}/assess")
    ClaimView assess(Caller caller, @PathVariable UUID id, @RequestBody AssessCommand cmd) {
        return claims.assess(caller, id, cmd);
    }

    /** 200 when this sign-off approves the claim, 202 when it is recorded but more authority is needed. */
    @PostMapping("/v1/claims/{id}/approve")
    ResponseEntity<ApproveResult> approve(Caller caller, @PathVariable UUID id,
                                          @RequestHeader(name = IdempotentResponses.KEY_HEADER, required = false) String key) {
        var r = claims.approve(caller, id, key);
        HttpStatus status = "APPROVED".equals(r.value().decision()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return IdempotentResponses.of(r, status);
    }

    @PostMapping("/v1/claims/{id}/settle")
    ClaimView settle(Caller caller, @PathVariable UUID id, @RequestBody(required = false) SettleRequest r) {
        return claims.settle(caller, id, r == null ? null : r.payoutToken());
    }
}
