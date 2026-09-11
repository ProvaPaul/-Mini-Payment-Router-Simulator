package com.misl.paymentrouter.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.misl.paymentrouter.model.Dfsp;

/**
 * What {@code POST /api/quotes} returns.
 *
 * <pre>
 * {
 *   "quoteId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
 *   "sourceProvider": "DFSP_A",
 *   "destinationProvider": "DFSP_B",
 *   "amount": 1000.00,
 *   "providerFee": 18.50,
 *   "routerFee": 5.00,
 *   "fee": 23.50,
 *   "total": 1023.50,
 *   "receivedAmount": 1000.00,
 *   "currency": "BDT",
 *   "createdAt": "2026-09-11T20:00:00Z",
 *   "expiresAt": "2026-09-11T20:05:00Z"
 * }
 * </pre>
 *
 * <h2>Why the fee is broken down instead of returned as one number</h2>
 * You asked for at least {@code amount}, {@code fee} and {@code total}, and those three are
 * here with exactly those names. The breakdown into {@code providerFee} and {@code routerFee}
 * is added because a payment product that shows an unexplained deduction is one users do not
 * trust - and because it makes the router's own role visible. A caller that only wants the
 * headline number reads {@code fee} and ignores the rest.
 *
 * <h2>Why {@code receivedAmount} exists when it always equals {@code amount}</h2>
 * Under our sender-pays model it is the same number, so it looks redundant. It is here because
 * "what does the receiver actually get?" is the question a sender most wants answered, and
 * making the contract answer it explicitly removes any doubt about which side bears the fee.
 * It also stops being redundant the moment a receiver-pays provider is added - and in Step 6
 * it is the exact figure the router sends to the destination DFSP.
 *
 * @param quoteId             handle for this quote; a transfer will reference it in Step 6
 * @param sourceProvider      echoed back so a stored response is self-contained
 * @param destinationProvider echoed back for the same reason
 * @param amount              the requested transfer amount
 * @param providerFee         what the source DFSP charges
 * @param routerFee           what this router charges
 * @param fee                 providerFee + routerFee - the total cost of sending
 * @param total               amount + fee - what the sender pays in full
 * @param receivedAmount      what the receiver is credited
 * @param currency            ISO code, fixed per deployment in this simulator
 * @param createdAt           when the quote was issued (UTC, ISO-8601)
 * @param expiresAt           after this instant the price is no longer honoured
 */
public record QuoteResponse(
        String quoteId,
        Dfsp sourceProvider,
        Dfsp destinationProvider,
        BigDecimal amount,
        BigDecimal providerFee,
        BigDecimal routerFee,
        BigDecimal fee,
        BigDecimal total,
        BigDecimal receivedAmount,
        String currency,
        Instant createdAt,
        Instant expiresAt
) {
}
