package com.misl.paymentrouter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.misl.paymentrouter.config.RouterProperties;
import com.misl.paymentrouter.config.RouterProperties.ProviderSettings;
import com.misl.paymentrouter.config.RouterProperties.QuoteSettings;
import com.misl.paymentrouter.model.Dfsp;
import com.misl.paymentrouter.model.FeeType;

/**
 * Exhaustive tests for the fee arithmetic. No Spring, no HTTP, no clock - just numbers in,
 * numbers out, in milliseconds.
 *
 * <p>This is the payoff of pulling {@link FeeCalculator} out as its own class. Fee maths is
 * where rounding mistakes hide, so it deserves many small tests; it gets them here cheaply
 * because nothing has to be booted to run one.
 *
 * <p>Note that the test builds its OWN {@link RouterProperties} rather than reading
 * application.yml. A test that depends on production config silently changes meaning whenever
 * someone edits a fee, and then no longer tests what its name claims.
 */
class FeeCalculatorTest {

    private static final RouterProperties PROPS = new RouterProperties(
            "payment-router", "test", "test", "BDT",
            new QuoteSettings(new BigDecimal("5.00"), Duration.ofMinutes(5)),
            Map.of(
                    Dfsp.DFSP_A, new ProviderSettings("AlphaPay", FeeType.PERCENTAGE,
                            new BigDecimal("1.85"), null, new BigDecimal("5.00"), new BigDecimal("50.00")),
                    Dfsp.DFSP_B, new ProviderSettings("BetaCash", FeeType.FLAT,
                            null, new BigDecimal("15.00"), null, null)
            )
    );

    private final FeeCalculator calculator = new FeeCalculator(PROPS);

    @Test
    @DisplayName("the worked example from the design doc: 1000 from A to B")
    void workedExample() {
        BigDecimal amount = new BigDecimal("1000");

        // 1000 x 1.85% = 18.50, which sits between the 5.00 floor and the 50.00 cap
        assertThat(calculator.providerFee(Dfsp.DFSP_A, amount)).isEqualByComparingTo("18.50");
        assertThat(calculator.routerFee()).isEqualByComparingTo("5.00");
        assertThat(calculator.totalFee(Dfsp.DFSP_A, amount)).isEqualByComparingTo("23.50");
        assertThat(calculator.totalPayable(Dfsp.DFSP_A, amount)).isEqualByComparingTo("1023.50");
    }

    @Test
    @DisplayName("direction changes the price - which is why a quote API has to exist")
    void directionChangesThePrice() {
        BigDecimal amount = new BigDecimal("1000");

        // Same amount, opposite direction, different total. If this were not true, the
        // frontend could hard-code the fee and POST /api/quotes would be pointless.
        assertThat(calculator.totalPayable(Dfsp.DFSP_A, amount)).isEqualByComparingTo("1023.50");
        assertThat(calculator.totalPayable(Dfsp.DFSP_B, amount)).isEqualByComparingTo("1020.00");
    }

    @ParameterizedTest(name = "A: {0} -> providerFee {1}")
    @CsvSource({
            // amount,   expected provider fee      reason
            "   100.00,   5.00",   // 1.85 raw, lifted to the 5.00 minimum
            "   270.00,   5.00",   // 4.995 raw, still under the floor
            "   271.00,   5.01",   // 5.0135 -> rounds to 5.01, just above the floor
            "  1000.00,  18.50",   // the plain case
            "  2702.00,  49.99",   // 49.987 -> rounds to 49.99, just under the cap
            "  2800.00,  50.00",   // 51.80 raw, capped
            " 10000.00,  50.00",   // far above the cap
    })
    @DisplayName("percentage provider respects its floor and ceiling")
    void percentageBounds(BigDecimal amount, BigDecimal expectedFee) {
        assertThat(calculator.providerFee(Dfsp.DFSP_A, amount)).isEqualByComparingTo(expectedFee);
    }

    @ParameterizedTest(name = "B: {0} -> providerFee always 15.00")
    @CsvSource({"1.00", "100.00", "1000.00", "999999.00"})
    @DisplayName("flat provider charges the same regardless of amount")
    void flatIsConstant(BigDecimal amount) {
        assertThat(calculator.providerFee(Dfsp.DFSP_B, amount)).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("rounds half up to two decimal places")
    void roundsHalfUpToTwoDecimals() {
        // 333 x 1.85% = 6.1605 -> 6.16
        assertThat(calculator.providerFee(Dfsp.DFSP_A, new BigDecimal("333"))).isEqualByComparingTo("6.16");

        // 350 x 1.85% = 6.475 -> HALF_UP gives 6.48, not 6.47
        assertThat(calculator.providerFee(Dfsp.DFSP_A, new BigDecimal("350"))).isEqualByComparingTo("6.48");
    }

    @Test
    @DisplayName("every emitted amount is normalised to exactly two decimal places")
    void amountsAreNormalisedToTwoDecimals() {
        // A request of "1000" must come back as 1000.00 so the JSON is uniformly shaped.
        assertThat(calculator.money(new BigDecimal("1000")).toPlainString()).isEqualTo("1000.00");
        assertThat(calculator.totalPayable(Dfsp.DFSP_B, new BigDecimal("1000")).toPlainString())
                .isEqualTo("1020.00");
    }

    @Test
    @DisplayName("BigDecimal keeps money exact where double would not")
    void bigDecimalIsExact() {
        // The canonical demonstration: 0.1 + 0.2 != 0.3 in binary floating point.
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);
        assertThat(new BigDecimal("0.1").add(new BigDecimal("0.2"))).isEqualByComparingTo("0.3");

        // And in our own arithmetic, a tiny amount through the flat-fee provider:
        //   0.10 (amount) + 15.00 (BetaCash flat) + 5.00 (router) = 20.10, exactly.
        // Note this is a case where the fee dwarfs the transfer - a real product would refuse
        // it. We do not, and that is worth knowing about rather than hiding.
        BigDecimal total = calculator.totalPayable(Dfsp.DFSP_B, new BigDecimal("0.10"));
        assertThat(total).isEqualByComparingTo("20.10");
    }

    @Test
    @DisplayName("a provider with no configured pricing is rejected, not silently priced at zero")
    void unconfiguredProviderIsRejected() {
        RouterProperties incomplete = new RouterProperties(
                "payment-router", "test", "test", "BDT",
                new QuoteSettings(new BigDecimal("5.00"), Duration.ofMinutes(5)),
                Map.of() // no providers configured at all
        );

        assertThatThrownBy(() -> new FeeCalculator(incomplete).providerFee(Dfsp.DFSP_A, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DFSP_A");
    }
}
