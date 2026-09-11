package com.misl.paymentrouter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import com.misl.paymentrouter.config.RouterProperties;
import com.misl.paymentrouter.config.RouterProperties.ProviderSettings;
import com.misl.paymentrouter.model.Dfsp;

/**
 * Works out what a transfer costs. Pure arithmetic - no storage, no clock, no randomness.
 *
 * <h2>Why this is a separate class from QuoteService</h2>
 * <ul>
 *   <li><b>It has exactly one job, and it is the one most likely to be wrong.</b> Fee maths is
 *       where rounding bugs and off-by-a-paisa errors live. Isolating it means it can be
 *       tested exhaustively - see {@code FeeCalculatorTest} - without constructing a quote,
 *       a store or a clock.</li>
 *   <li><b>Two callers need it.</b> Quoting needs it now; Step 6's transfer needs the identical
 *       numbers. If the arithmetic were inlined into {@code QuoteService}, transfer would end
 *       up with a second copy, the copies would drift, and the router would quote 23.50 and
 *       charge 24.00. <b>That is a real-money bug, and duplication is how it happens.</b></li>
 *   <li><b>It is deterministic.</b> Same inputs, same output, always. That makes it trivial to
 *       reason about and trivial to test.</li>
 * </ul>
 *
 * <h2>The rule (documented assumption)</h2>
 * <pre>
 *   providerFee = source provider's charge for this amount   (percentage or flat, per config)
 *   routerFee   = flat switching fee                          (config)
 *   fee         = providerFee + routerFee
 *   total       = amount + fee                                (SENDER PAYS)
 *   received    = amount                                      (receiver gets the full amount)
 * </pre>
 *
 * <p><b>Only the SOURCE provider's fee is charged.</b> This is the "sender pays" model that
 * bKash's Send Money uses: the person initiating the transfer bears the cost, and the receiver
 * gets exactly the number they were promised. The destination provider is not consulted for
 * pricing. A real interoperability scheme often splits fees across both sides, but sender-pays
 * is simpler to explain, simpler to display in a UI, and unambiguous.
 *
 * <p>Consequence worth noting: <b>the price depends on direction.</b> 1000 from A to B is not
 * the same price as 1000 from B to A, because A and B price differently. That asymmetry is
 * precisely why a quote endpoint has to exist rather than a constant in the frontend.
 */
@Service
public class FeeCalculator {

    /**
     * Two decimal places, the standard for a currency with 100 minor units.
     * Applied consistently so every number we emit has the same shape.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * HALF_UP rounds 0.005 upward - the rounding rule people learn in school, and the one a
     * customer expects when they check the arithmetic by hand. Java's default for
     * {@code BigDecimal.divide} is to throw rather than guess, so the mode must be explicit.
     */
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final RouterProperties properties;

    public FeeCalculator(RouterProperties properties) {
        this.properties = properties;
    }

    /**
     * The fee charged by the sending provider for moving {@code amount}.
     *
     * @throws IllegalArgumentException if the provider has no configured pricing rules
     */
    public BigDecimal providerFee(Dfsp sourceProvider, BigDecimal amount) {
        ProviderSettings settings = settingsFor(sourceProvider);

        // An exhaustive switch over the enum: adding a new FeeType makes this stop compiling
        // until the new case is handled. The compiler becomes the checklist.
        BigDecimal fee = switch (settings.feeType()) {
            case FLAT -> settings.flatFee();
            case PERCENTAGE -> amount
                    .multiply(settings.percentage())
                    .divide(HUNDRED, MONEY_SCALE, ROUNDING);
        };

        fee = applyBounds(fee, settings);
        return money(fee);
    }

    /** The router's own flat switching fee. Same for every provider pair. */
    public BigDecimal routerFee() {
        return money(properties.quote().routerFee());
    }

    /** providerFee + routerFee: everything the sender pays on top of the amount. */
    public BigDecimal totalFee(Dfsp sourceProvider, BigDecimal amount) {
        return money(providerFee(sourceProvider, amount).add(routerFee()));
    }

    /** amount + totalFee: the full sum debited from the sender. */
    public BigDecimal totalPayable(Dfsp sourceProvider, BigDecimal amount) {
        return money(money(amount).add(totalFee(sourceProvider, amount)));
    }

    /**
     * Normalises any amount to the money scale.
     *
     * <p>Exposed so callers emit consistently shaped numbers: a request of {@code 1000}
     * becomes {@code 1000.00}, so every figure in a response lines up. Without this, JSON
     * would carry a mix of {@code 1000} and {@code 18.50} and the UI would have to normalise.
     */
    public BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    /** Clamps a computed fee into [minFee, maxFee]; a null bound means "unbounded". */
    private BigDecimal applyBounds(BigDecimal fee, ProviderSettings settings) {
        if (settings.minFee() != null && fee.compareTo(settings.minFee()) < 0) {
            return settings.minFee();
        }
        if (settings.maxFee() != null && fee.compareTo(settings.maxFee()) > 0) {
            return settings.maxFee();
        }
        return fee;
    }

    private ProviderSettings settingsFor(Dfsp provider) {
        ProviderSettings settings = properties.providers().get(provider);
        if (settings == null) {
            // Misconfiguration, not bad user input: the enum constant exists but application.yml
            // has no pricing for it. Step 4 gives this its own error code and status.
            throw new IllegalArgumentException("No fee configuration for provider " + provider);
        }
        return settings;
    }
}
