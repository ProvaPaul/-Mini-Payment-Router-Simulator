package com.misl.paymentrouter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A price promise the router has issued. This is the <b>internal domain object</b>.
 *
 * <p>It is deliberately a different type from {@code QuoteResponse}, the DTO we send over
 * HTTP. Right now the two look similar, and the mapping between them is almost one-to-one.
 * That is not a sign the separation is pointless - it is a sign we are early. The boundary
 * earns its keep in Step 6, when this record grows fields that must never reach a browser:
 * a {@code status} (ACTIVE / CONSUMED) so a quote can only be spent once, a
 * {@code consumedAt} timestamp, and the raw JSON each DFSP returned.
 *
 * <p>Because the mapping is written by hand in {@code QuoteService.toResponse(...)}, adding a
 * field here <b>cannot</b> accidentally publish it. Had we returned this object directly from
 * the controller, every future internal field would have been exposed the moment it was added,
 * silently. That is the entire argument for the DTO boundary, and it is a real bug class -
 * not a theoretical one.
 *
 * <p>Note the types: {@link Dfsp} enums rather than Strings, {@link Instant} rather than a
 * formatted String, {@link BigDecimal} rather than double. The domain model speaks in precise
 * types; converting them to wire-friendly shapes is the DTO's job, not the domain's.
 *
 * @param quoteId           server-generated UUID; the handle a transfer will quote back at us
 * @param sourceProvider    the DFSP the money leaves
 * @param destinationProvider the DFSP the money arrives at
 * @param amount            what the receiver should end up with
 * @param providerFee       the source DFSP's charge for this amount
 * @param routerFee         this router's switching fee
 * @param totalFee          providerFee + routerFee
 * @param totalPayable      amount + totalFee - what the sender is debited (sender-pays)
 * @param currency          ISO currency code; fixed to one value in this simulator
 * @param createdAt         when the promise was made
 * @param expiresAt         when the promise stops being honoured
 */
public record Quote(
        String quoteId,
        Dfsp sourceProvider,
        Dfsp destinationProvider,
        BigDecimal amount,
        BigDecimal providerFee,
        BigDecimal routerFee,
        BigDecimal totalFee,
        BigDecimal totalPayable,
        String currency,
        Instant createdAt,
        Instant expiresAt
) {

    /**
     * Whether this quote's price promise has lapsed, according to the supplied time.
     *
     * <p>The caller passes "now" rather than this method calling {@code Instant.now()} itself.
     * Same reasoning as the injected {@code Clock} in Step 2: a method that reads the real
     * system clock cannot be tested without waiting in real time. Here the rule lives with the
     * data it describes, but the <i>time source</i> stays the caller's choice.
     *
     * <p>Nothing enforces this yet - Step 6's transfer is what will reject an expired quote.
     * It lives here now because "what makes a quote invalid" is knowledge about a quote.
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
