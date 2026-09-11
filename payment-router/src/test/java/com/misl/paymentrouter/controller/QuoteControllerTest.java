package com.misl.paymentrouter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.misl.paymentrouter.dto.QuoteResponse;
import com.misl.paymentrouter.model.Dfsp;
import com.misl.paymentrouter.service.QuoteService;

/**
 * Web-layer tests: routing, status codes, JSON field names, serialisation.
 *
 * <p>{@link QuoteService} is mocked, so nothing here depends on fee arithmetic. If a fee rule
 * changes, these tests keep passing - as they should, because they are not about fees. That
 * separation is only possible because the controller receives the service through its
 * constructor.
 */
@WebMvcTest(QuoteController.class)
class QuoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuoteService quoteService;

    private static QuoteResponse sampleResponse() {
        return new QuoteResponse(
                "11111111-2222-3333-4444-555555555555",
                Dfsp.DFSP_A,
                Dfsp.DFSP_B,
                new BigDecimal("1000.00"),
                new BigDecimal("18.50"),
                new BigDecimal("5.00"),
                new BigDecimal("23.50"),
                new BigDecimal("1023.50"),
                new BigDecimal("1000.00"),
                "BDT",
                Instant.parse("2026-09-11T20:00:00Z"),
                Instant.parse("2026-09-11T20:05:00Z")
        );
    }

    @Test
    @DisplayName("POST /api/quotes returns 200 with the full quote body")
    void createQuoteReturnsOk() throws Exception {
        given(quoteService.createQuote(any())).willReturn(sampleResponse());

        mockMvc.perform(post("/api/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceProvider": "DFSP_A",
                                  "destinationProvider": "DFSP_B",
                                  "amount": 1000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteId").value("11111111-2222-3333-4444-555555555555"))
                .andExpect(jsonPath("$.sourceProvider").value("DFSP_A"))
                .andExpect(jsonPath("$.destinationProvider").value("DFSP_B"))
                .andExpect(jsonPath("$.amount").value(1000.00))
                .andExpect(jsonPath("$.providerFee").value(18.50))
                .andExpect(jsonPath("$.routerFee").value(5.00))
                .andExpect(jsonPath("$.fee").value(23.50))
                .andExpect(jsonPath("$.total").value(1023.50))
                .andExpect(jsonPath("$.receivedAmount").value(1000.00))
                .andExpect(jsonPath("$.currency").value("BDT"))
                // ISO-8601, not an epoch number - proves the Jackson setting is in effect
                .andExpect(jsonPath("$.expiresAt").value("2026-09-11T20:05:00Z"));
    }

    @Test
    @DisplayName("an unknown provider name is rejected as 400 before reaching the service")
    void unknownProviderIsBadRequest() throws Exception {
        // "DFSP_Z" is not a Dfsp constant, so Jackson fails while parsing the body and Spring
        // answers 400 on its own. This is the enum earning its keep: with a String field the
        // bad value would have travelled all the way into the business logic.
        mockMvc.perform(post("/api/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceProvider": "DFSP_Z",
                                  "destinationProvider": "DFSP_B",
                                  "amount": 1000
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("malformed JSON is rejected as 400")
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/quotes/{id} returns 200 when the quote exists")
    void getExistingQuote() throws Exception {
        given(quoteService.findQuote("abc")).willReturn(Optional.of(sampleResponse()));

        mockMvc.perform(get("/api/quotes/abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1023.50));
    }

    @Test
    @DisplayName("GET /api/quotes/{id} returns 404 when the quote is unknown")
    void getMissingQuoteReturns404() throws Exception {
        given(quoteService.findQuote("nope")).willReturn(Optional.empty());

        // The service reports absence with an empty Optional; turning that into 404 is the
        // controller's job, and this test is what pins that translation down.
        mockMvc.perform(get("/api/quotes/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET on the quotes collection is 405, not 404")
    void listingQuotesIsNotSupported() throws Exception {
        // We deliberately expose no "list all quotes" endpoint - it would hand every customer's
        // pricing to anyone who asked.
        //
        // The status is 405 Method Not Allowed rather than 404 Not Found, and that distinction
        // is correct: /api/quotes IS a mapped path - just not for GET. 404 would mean "no such
        // resource", which would be a lie. Spring works this out from the mappings by itself.
        mockMvc.perform(get("/api/quotes"))
                .andExpect(status().isMethodNotAllowed());
    }
}
