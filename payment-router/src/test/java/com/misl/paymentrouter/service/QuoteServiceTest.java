package com.misl.paymentrouter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.misl.paymentrouter.config.RouterProperties;
import com.misl.paymentrouter.config.RouterProperties.ProviderSettings;
import com.misl.paymentrouter.config.RouterProperties.QuoteSettings;
import com.misl.paymentrouter.dto.QuoteRequest;
import com.misl.paymentrouter.dto.QuoteResponse;
import com.misl.paymentrouter.model.Dfsp;
import com.misl.paymentrouter.model.FeeType;
import com.misl.paymentrouter.store.QuoteStore;

/**
 * Tests the quote lifecycle: rules, identity, expiry, storage and mapping.
 * Again: no Spring, no HTTP, no waiting.
 */
class QuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T20:00:00Z");

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

    private final QuoteStore store = new QuoteStore();
    private final QuoteService service = new QuoteService(
            new FeeCalculator(PROPS),
            store,
            PROPS,
            Clock.fixed(NOW, ZoneOffset.UTC)   // time is an input, so the test controls it
    );

    private static QuoteRequest request(Dfsp from, Dfsp to, String amount) {
        return new QuoteRequest(from, to, new BigDecimal(amount));
    }

    @Test
    @DisplayName("prices 1000 from A to B and returns every field of the contract")
    void createsQuoteWithFullBreakdown() {
        QuoteResponse response = service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_B, "1000"));

        assertThat(response.quoteId()).isNotBlank();
        assertThat(response.sourceProvider()).isEqualTo(Dfsp.DFSP_A);
        assertThat(response.destinationProvider()).isEqualTo(Dfsp.DFSP_B);
        assertThat(response.amount()).isEqualByComparingTo("1000.00");
        assertThat(response.providerFee()).isEqualByComparingTo("18.50");
        assertThat(response.routerFee()).isEqualByComparingTo("5.00");
        assertThat(response.fee()).isEqualByComparingTo("23.50");
        assertThat(response.total()).isEqualByComparingTo("1023.50");
        assertThat(response.receivedAmount()).isEqualByComparingTo("1000.00");
        assertThat(response.currency()).isEqualTo("BDT");
    }

    @Test
    @DisplayName("the arithmetic is internally consistent: total = amount + fee, fee = parts")
    void totalsReconcile() {
        QuoteResponse r = service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_B, "1234.56"));

        // These invariants must hold for ANY input. If they ever fail, the router is
        // promising a number it cannot justify - the single worst bug this service could have.
        assertThat(r.fee()).isEqualByComparingTo(r.providerFee().add(r.routerFee()));
        assertThat(r.total()).isEqualByComparingTo(r.amount().add(r.fee()));
        assertThat(r.receivedAmount()).isEqualByComparingTo(r.amount());
    }

    @Test
    @DisplayName("expiry is exactly the configured TTL after creation")
    void quoteExpiresAfterConfiguredTtl() {
        QuoteResponse response = service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_B, "1000"));

        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));

        // Asserting on a five-minute expiry without waiting five minutes is only possible
        // because the Clock is injected rather than read from the system.
        assertThat(store.findById(response.quoteId()).orElseThrow().isExpired(NOW)).isFalse();
        assertThat(store.findById(response.quoteId()).orElseThrow()
                .isExpired(NOW.plusSeconds(299))).isFalse();
        assertThat(store.findById(response.quoteId()).orElseThrow()
                .isExpired(NOW.plusSeconds(300))).isTrue();
    }

    @Test
    @DisplayName("each quote gets a unique id and is retrievable afterwards")
    void quotesAreStoredAndUniquelyIdentified() {
        QuoteResponse first = service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_B, "1000"));
        QuoteResponse second = service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_B, "1000"));

        assertThat(first.quoteId()).isNotEqualTo(second.quoteId());
        assertThat(store.size()).isEqualTo(2);

        assertThat(service.findQuote(first.quoteId())).isPresent();
        assertThat(service.findQuote(first.quoteId()).orElseThrow().total())
                .isEqualByComparingTo("1023.50");
    }

    @Test
    @DisplayName("an unknown quote id yields an empty Optional, not an exception")
    void unknownQuoteIdIsEmpty() {
        assertThat(service.findQuote("does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("rejects a transfer to the same provider")
    void rejectsSameProvider() {
        assertThatThrownBy(() -> service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_A, "1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    @DisplayName("rejects zero and negative amounts")
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_B, "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");

        // "0.00" is worth testing separately: BigDecimal.equals(ZERO) would be FALSE here
        // because equals compares scale too. Only compareTo gets this right.
        assertThatThrownBy(() -> service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_B, "0.00")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_B, "-100")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects missing fields")
    void rejectsMissingFields() {
        assertThatThrownBy(() -> service.createQuote(new QuoteRequest(null, Dfsp.DFSP_B, BigDecimal.TEN)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.createQuote(new QuoteRequest(Dfsp.DFSP_A, Dfsp.DFSP_B, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nothing is stored when the request is rejected")
    void rejectedRequestsAreNotStored() {
        assertThatThrownBy(() -> service.createQuote(request(Dfsp.DFSP_A, Dfsp.DFSP_A, "1000")))
                .isInstanceOf(IllegalArgumentException.class);

        // Validating BEFORE doing any work is what guarantees this. A rejected request must
        // leave no trace - otherwise the store fills with quotes that were never issued.
        assertThat(store.size()).isZero();
    }
}
