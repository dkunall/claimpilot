package com.claimpilot.quote.adapter;

import com.claimpilot.idempotency.adapter.IdempotentResponses;
import com.claimpilot.quote.application.BindService;
import com.claimpilot.quote.application.BindService.BindRequest;
import com.claimpilot.quote.application.BindService.BindResult;
import com.claimpilot.quote.application.QuoteService;
import com.claimpilot.quote.application.QuoteService.QuoteRequest;
import com.claimpilot.quote.domain.Quote;
import com.claimpilot.shared.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class QuoteController {

    private final QuoteService quotes;
    private final BindService binder;

    QuoteController(QuoteService quotes, BindService binder) {
        this.quotes = quotes;
        this.binder = binder;
    }

    @PostMapping("/v1/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    Quote create(Caller caller, @RequestBody QuoteRequest r) {
        return quotes.create(caller, r);
    }

    @GetMapping("/v1/quotes/{id}")
    Quote get(Caller caller, @PathVariable UUID id) {
        return quotes.get(caller, id);
    }

    @PostMapping("/v1/quotes/{id}/bind")
    ResponseEntity<BindResult> bind(Caller caller, @PathVariable UUID id,
                                    @RequestHeader(name = IdempotentResponses.KEY_HEADER, required = false) String key,
                                    @RequestBody BindRequest r) {
        return IdempotentResponses.of(binder.bind(caller, id, key, r), HttpStatus.CREATED);
    }
}
