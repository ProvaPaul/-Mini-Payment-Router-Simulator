package com.misl.paymentrouter.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.misl.paymentrouter.config.RouterProperties;
import com.misl.paymentrouter.dto.QuoteRequest;
import com.misl.paymentrouter.dto.QuoteResponse;
import com.misl.paymentrouter.model.Dfsp;
import com.misl.paymentrouter.model.Quote;
import com.misl.paymentrouter.store.QuoteStore;

/**
 * Owns the lifecycle of a quote: check it makes sense, price it, give it an identity and an
 * expiry, remember it, and shape it for the caller.
 *
 * <p>As with {@code HealthService}, notice the imports: nothing from {@code jakarta.servlet},
 * nothing from {@code org.springframework.web}. <b>This class does not know it is reachable
 * over HTTP.</b> It could be driven by a scheduled job, a CLI or a test, unchanged. That is
 * what makes the rules below testable without a server - see {@code QuoteServiceTest}.
 *
 * <p>Four collaborators arrive through the constructor, each with one responsibility:
 * {@link FeeCalculator} does arithmetic, {@link QuoteStore} does storage, {@link Clock} supplies
 * time, {@link RouterProperties} supplies configuration. This class does none of those things
 * itself - it decides the <i>order</i> and the <i>rules</i>.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private final FeeCalculator feeCalculator;
    private final QuoteStore quoteStore;
    private final RouterProperties properties;
    private final Clock clock;

    public QuoteService(FeeCalculator feeCalculator,
                        QuoteStore quoteStore,
                        RouterProperties properties,
                        Clock clock) {
        this.feeCalculator = feeCalculator;
        this.quoteStore = quoteStore;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Prices a transfer and issues a quote.
     *
     * <p>The order matters and is deliberate:
     * <ol>
     *   <li><b>Reject nonsense first.</b> Never do work on input that cannot succeed.</li>
     *   <li><b>Price it.</b> Delegated entirely to {@link FeeCalculator}.</li>
     *   <li><b>Give it an identity and a deadline.</b></li>
     *   <li><b>Store it</b>, so the id means something.</li>
     *   <li><b>Map to the wire shape</b> - the last step, and the only place the public
     *       contract is constructed.</li>
     * </ol>
     */
    public QuoteResponse createQuote(QuoteRequest request) {
        validate(request);

        Dfsp source = request.sourceProvider();
        Dfsp destination = request.destinationProvider();
        BigDecimal amount = feeCalculator.money(request.amount());

        BigDecimal providerFee = feeCalculator.providerFee(source, amount);
        BigDecimal routerFee = feeCalculator.routerFee();
        BigDecimal totalFee = feeCalculator.totalFee(source, amount);
        BigDecimal totalPayable = feeCalculator.totalPayable(source, amount);

        Instant now = clock.instant();

        Quote quote = new Quote(
                UUID.randomUUID().toString(),
                source,
                destination,
                amount,
                providerFee,
                routerFee,
                totalFee,
                totalPayable,
                properties.currency(),
                now,
                now.plus(properties.quote().ttl())
        );

        quoteStore.save(quote);

        // The business narrative, at INFO. When Step 7 adds a correlation id and a log file,
        // this single line is what lets you reconstruct what the router promised and when.
        log.info("Quote created: id={} {}->{} amount={} providerFee={} routerFee={} total={} expiresAt={}",
                quote.quoteId(), source, destination, amount, providerFee, routerFee, totalPayable, quote.expiresAt());

        return toResponse(quote);
    }

    /** Looks up a previously issued quote. Empty if the id is unknown. */
    public Optional<QuoteResponse> findQuote(String quoteId) {
        return quoteStore.findById(quoteId).map(this::toResponse);
    }

    /**
     * Rules that must hold before we will price anything.
     *
     * <p>These are <b>business</b> rules, which is why they live here and not in the
     * controller: "you cannot send money to yourself through a router" is a statement about
     * payments, not about HTTP.
     *
     * <p>They throw plain {@link IllegalArgumentException} today, which Spring turns into an
     * ugly {@code 500}. That is temporary and deliberate: <b>Step 4 replaces these with typed
     * exceptions and a global handler</b> that produce {@code 400} with a readable body. Seeing
     * the bad behaviour first makes it obvious what Step 4 is actually buying us.
     */
    private void validate(QuoteRequest request) {
        if (request.sourceProvider() == null || request.destinationProvider() == null) {
            throw new IllegalArgumentException("sourceProvider and destinationProvider are required");
        }
        if (request.amount() == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            // compareTo, not equals: BigDecimal.equals("0.00") is false against ZERO because
            // equals compares scale as well as value. For money comparisons, always compareTo.
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (request.sourceProvider() == request.destinationProvider()) {
            // This router exists to move money BETWEEN providers. A same-provider transfer is
            // an internal matter for that provider and never needs a switch.
            throw new IllegalArgumentException("sourceProvider and destinationProvider must be different");
        }
    }

    /**
     * The single place the internal {@link Quote} becomes the public {@link QuoteResponse}.
     *
     * <p>Because this mapping is explicit and hand-written, a field added to {@code Quote}
     * is invisible to callers until someone deliberately adds it here. In Step 6 the quote
     * gains a consumption status and raw provider payloads; this method is what guarantees
     * they stay internal.
     */
    private QuoteResponse toResponse(Quote quote) {
        return new QuoteResponse(
                quote.quoteId(),
                quote.sourceProvider(),
                quote.destinationProvider(),
                quote.amount(),
                quote.providerFee(),
                quote.routerFee(),
                quote.totalFee(),
                quote.totalPayable(),
                quote.amount(),          // receivedAmount: sender-pays, so the receiver gets it all
                quote.currency(),
                quote.createdAt(),
                quote.expiresAt()
        );
    }
}
