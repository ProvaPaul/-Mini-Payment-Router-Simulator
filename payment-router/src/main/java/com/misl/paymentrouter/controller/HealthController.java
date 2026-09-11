package com.misl.paymentrouter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.misl.paymentrouter.dto.HealthResponse;
import com.misl.paymentrouter.service.HealthService;

/**
 * HTTP entry point for the service's own health.
 *
 * <p>A controller is a <b>translator</b>, and nothing more. Its entire job:
 * turn an incoming HTTP request into a Java call, and turn the Java result back into an
 * HTTP response with the right status code. All decision-making is delegated downwards.
 *
 * <p>Read the method below and notice there is not a single {@code if}. That is the
 * signal that the separation is working.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HealthService healthService;

    /**
     * Constructor injection again - and note the TYPE. If {@code HealthService} were an
     * interface, this controller would not know or care which implementation arrived.
     * That is exactly the mechanism we rely on in Step 6, where the router depends on a
     * {@code DfspConnector} interface and Spring supplies the right implementation.
     */
    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * {@code GET /api/health}
     *
     * <p>{@code @GetMapping("/health")} combines with the class-level
     * {@code @RequestMapping("/api")} to produce the full path {@code /api/health}.
     * Keeping the {@code /api} prefix in one place means every endpoint we add stays
     * consistent, and in Step 9 nginx can proxy a single prefix to the backend.
     *
     * <p>Because the class is a {@code @RestController}, the returned
     * {@link HealthResponse} is handed to Jackson and serialised to JSON automatically.
     * We return the object, not a String, and never touch the response body ourselves.
     *
     * <p>The default status for a successful return is {@code 200 OK}, which is correct
     * here, so we do not wrap the value in a {@code ResponseEntity}. We will need
     * {@code ResponseEntity} in Step 3, where creating a transfer must answer
     * {@code 201 Created}.
     */
    @GetMapping("/health")
    public HealthResponse health() {
        log.info("GET /api/health");
        return healthService.currentHealth();
    }
}
