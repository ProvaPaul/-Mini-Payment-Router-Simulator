package com.misl.paymentrouter.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.misl.paymentrouter.dto.HealthResponse;
import com.misl.paymentrouter.service.HealthService;

/**
 * Tests the WEB LAYER only: routing, status code, and JSON shape.
 *
 * <p>{@code @WebMvcTest(HealthController.class)} is a "slice test". Unlike
 * {@code @SpringBootTest}, it starts only Spring MVC and only the named controller -
 * no services, no Actuator, no other beans. It is dramatically faster, and when it fails
 * you know the problem is in the controller, because nothing else was loaded.
 *
 * <p>{@code @MockitoBean} puts a Mockito mock of {@link HealthService} into the context in
 * place of the real one. This is the payoff of dependency injection: because the controller
 * receives its collaborator through its constructor rather than creating it, we can hand it
 * a fake and test the controller in genuine isolation.
 *
 * <p>({@code @MockitoBean} replaced the older {@code @MockBean}, deprecated in Spring Boot 3.4.
 * Tutorials written before 2025 will show {@code @MockBean}.)
 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    /** Sends simulated HTTP requests through the real Spring MVC stack - no socket, no port. */
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    @DisplayName("GET /api/health returns 200 with the expected JSON body")
    void healthReturnsOk() throws Exception {
        // GIVEN: a fixed, predictable answer from the (mocked) service.
        // The controller's job is only to pass this through - so that is all we verify.
        HealthResponse stubbed = new HealthResponse(
                "UP",
                "payment-router",
                "0.1.0-step2",
                "local",
                Instant.parse("2026-09-11T20:00:00Z"),
                42L
        );
        given(healthService.currentHealth()).willReturn(stubbed);

        // WHEN + THEN
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("payment-router"))
                .andExpect(jsonPath("$.version").value("0.1.0-step2"))
                .andExpect(jsonPath("$.environment").value("local"))
                .andExpect(jsonPath("$.uptimeSeconds").value(42))
                // Proves spring.jackson.serialization.write-dates-as-timestamps=false is in
                // effect: an ISO-8601 string, not the number 1789156800.000000000.
                .andExpect(jsonPath("$.timestamp").value("2026-09-11T20:00:00Z"));
    }

    @Test
    @DisplayName("an unmapped path returns 404")
    void unknownPathReturns404() throws Exception {
        // Confirms the /api prefix is genuinely required - i.e. that @RequestMapping("/api")
        // on the class is doing what we think it is.
        mockMvc.perform(get("/health"))
                .andExpect(status().isNotFound());
    }
}
