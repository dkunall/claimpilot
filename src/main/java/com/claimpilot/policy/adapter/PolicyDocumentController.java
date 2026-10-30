package com.claimpilot.policy.adapter;

import com.claimpilot.payment.application.PaymentService;
import com.claimpilot.payment.domain.Payment;
import com.claimpilot.policy.application.PolicyDocuments;
import com.claimpilot.policy.application.PolicyRepository;
import com.claimpilot.policy.application.PolicyService;
import com.claimpilot.policy.domain.Policy;
import com.claimpilot.shared.error.ApiException;
import com.claimpilot.shared.security.Caller;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
class PolicyDocumentController {

    private final PolicyService policies;
    private final PolicyRepository repo;
    private final PolicyDocuments documents;
    private final PaymentService payments;

    PolicyDocumentController(PolicyService policies, PolicyRepository repo, PolicyDocuments documents,
                             PaymentService payments) {
        this.policies = policies;
        this.repo = repo;
        this.documents = documents;
        this.payments = payments;
    }

    record Link(URI url, Instant expiresAt) {
    }

    /** A short-lived link to the schedule for the policy's current version. */
    @GetMapping("/v1/policies/{id}/schedule")
    Link schedule(Caller caller, @PathVariable UUID id) {
        Policy p = policies.visible(caller, id);
        var link = documents.scheduleLink(p, repo.current(id));
        return new Link(link.url(), link.expiresAt());
    }

    @GetMapping("/v1/policies/{id}/payments")
    List<Payment> payments(Caller caller, @PathVariable UUID id) {
        policies.visible(caller, id);
        return payments.forPolicy(id);
    }

    @GetMapping("/v1/payments/{id}/receipt")
    ResponseEntity<byte[]> receipt(Caller caller, @PathVariable UUID id) {
        Payment pay = payments.find(id).filter(Payment::succeeded).orElseThrow(() -> ApiException.notFound("receipt"));
        Policy p = policies.visible(caller, pay.policyId());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pay.receiptNo() + ".pdf\"")
                .body(documents.receipt(p, pay));
    }
}
