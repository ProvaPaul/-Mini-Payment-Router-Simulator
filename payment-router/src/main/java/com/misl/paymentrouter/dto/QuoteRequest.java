package com.misl.paymentrouter.dto;

import java.math.BigDecimal;

import com.misl.paymentrouter.model.Dfsp;

/**
 * What a client sends to {@code POST /api/quotes}.
 *
 * <pre>
 * {
 *   "sourceProvider": "DFSP_A",
 *   "destinationProvider": "DFSP_B",
 *   "amount": 1000
 * }
 * </pre>
 *
 * <p>Jackson builds this record from the JSON body: it matches each JSON key to a record
 * component by name, converts {@code "DFSP_A"} to the {@link Dfsp} enum constant, and
 * converts the JSON number to a {@link BigDecimal}. An unknown provider string fails here,
 * during parsing, which Spring reports as {@code 400 Bad Request} without us writing anything.
 *
 * <p><b>Why {@link BigDecimal} and never {@code double}?</b> Binary floating point cannot
 * represent most decimal fractions exactly. In Java, {@code 0.1 + 0.2} evaluates to
 * {@code 0.30000000000000004}. Applied to money, those invisible fractions accumulate until
 * the books stop balancing - and "the totals are off by 0.01" is a genuinely miserable bug to
 * chase. {@code BigDecimal} stores an exact decimal value with an explicit scale, which is why
 * every financial system in the world uses either it or integer minor units (poisha, cents).
 *
 * <p><b>Why no {@code @NotNull} / {@code @Positive} annotations yet?</b> Validation is Step 4.
 * Today a missing or negative amount is caught by a plain guard in {@code QuoteService} and
 * surfaces as an ugly {@code 500}. Seeing that failure before we fix it is the point - Step 4
 * turns it into a clean {@code 400} with a readable message.
 *
 * @param sourceProvider      the DFSP the money leaves
 * @param destinationProvider the DFSP the money should arrive at
 * @param amount              how much the receiver should end up with, in the router's currency
 */
public record QuoteRequest(
        Dfsp sourceProvider,
        Dfsp destinationProvider,
        BigDecimal amount
) {
}
