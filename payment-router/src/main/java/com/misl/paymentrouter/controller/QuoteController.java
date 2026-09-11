package com.misl.paymentrouter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.misl.paymentrouter.dto.QuoteRequest;
import com.misl.paymentrouter.dto.QuoteResponse;
import com.misl.paymentrouter.service.QuoteService;

/**
 * HTTP entry point for quotes.
 *
 * <p>Read both methods and note what is absent: no arithmetic, no fee rules, no
 * {@code BigDecimal}, no clock, no storage, no business {@code if}. The controller decides
 * <b>URL, HTTP method, and status code</b> - and delegates every decision that involves money.
 *
 * <p>That is the whole reason business logic does not go here. This class is chained to HTTP:
 * it can only be exercised by making a request. Everything it contains becomes untestable
 * without a web layer, and unreachable from any other caller. Keeping it this thin means all
 * the interesting logic lives somewhere it can be tested in milliseconds.
 */
@RestController
@RequestMapping("/api")
public class QuoteController {

    private static final Logger log = LoggerFactory.getLogger(QuoteController.class);

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    /**
     * {@code POST /api/quotes} - price a transfer between two providers.
     *
     * <p>{@code @RequestBody} tells Spring to read the HTTP request body and have Jackson turn
     * it into a {@link QuoteRequest}. Without the annotation Spring would try to build the
     * object from query parameters instead, and every field would arrive null.
     *
     * <p><b>Why POST and not GET, given that quoting changes no balances?</b> Two reasons.
     * The request is a structured object, and GET has no body - you would be encoding a nested
     * payload into a query string. And the call is not read-only from the server's point of
     * view: <b>it creates a quote resource</b>, with an id and an expiry, that we store and
     * that a transfer will later consume.
     *
     * <p><b>Why 200 OK and not 201 Created?</b> 201 is for a resource the client will now
     * address and manage, and is expected to carry a {@code Location} header. A quote is a
     * short-lived price answer, consumed by passing its id to a different endpoint - not
     * something the client navigates to. Step 6's transfer, which does create a durable record
     * of a movement of money, will return 201.
     */
    @PostMapping("/quotes")
    public QuoteResponse createQuote(@RequestBody QuoteRequest request) {
        log.info("POST /api/quotes {}->{} amount={}",
                request.sourceProvider(), request.destinationProvider(), request.amount());
        return quoteService.createQuote(request);
    }

    /**
     * {@code GET /api/quotes/{quoteId}} - retrieve a previously issued quote.
     *
     * <p>{@code @PathVariable} binds the {@code {quoteId}} segment of the URL to the parameter.
     *
     * <p>Here we do return a {@link ResponseEntity}, because this method has to choose between
     * two status codes. Mapping "the service found nothing" onto {@code 404 Not Found} is
     * exactly the controller's job: translating a domain outcome into the right HTTP answer.
     * The service reports absence with an {@link java.util.Optional}; it has no opinion about
     * status codes, and should not.
     */
    @GetMapping("/quotes/{quoteId}")
    public ResponseEntity<QuoteResponse> getQuote(@PathVariable String quoteId) {
        log.info("GET /api/quotes/{}", quoteId);
        return quoteService.findQuote(quoteId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
