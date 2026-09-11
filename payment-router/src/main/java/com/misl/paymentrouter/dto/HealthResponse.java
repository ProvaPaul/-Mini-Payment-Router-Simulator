package com.misl.paymentrouter.dto;

import java.time.Instant;

/**
 * The JSON body returned by {@code GET /api/health}.
 *
 * <p>A <b>DTO</b> (Data Transfer Object) is the deliberate, narrow, public shape of our API.
 * It is a separate type from our internal model on purpose:
 * <ul>
 *   <li><b>It is a contract.</b> Reading this file tells you exactly what a caller receives.
 *       Nothing leaks by accident.</li>
 *   <li><b>It decouples.</b> We can rename or restructure internal classes without changing
 *       what the frontend sees - and vice versa.</li>
 *   <li><b>It is a safety boundary.</b> Later, {@code Quote} will hold internal fields
 *       (raw DFSP responses, consumption status) that must never reach the browser. Returning
 *       internal objects directly is how such fields get published by accident.</li>
 * </ul>
 *
 * <p><b>Why a record?</b> It is immutable and one line long. A response object should never be
 * modified after it is built, and Jackson (the JSON library Spring Boot configures for us)
 * reads record accessors to produce field names. No getters to write, no Lombok needed.
 *
 * @param status        always "UP" for now; a real readiness check arrives once we have
 *                      DFSP connectors to probe (Step 6)
 * @param service       which service answered - valuable once four containers are running
 * @param version       which build answered
 * @param environment   local / docker
 * @param timestamp     server time in UTC, ISO-8601 (configured in application.yml)
 * @param uptimeSeconds seconds since this instance started; a sudden reset to 0 means it
 *                      restarted, which matters a lot to us because all our state is in memory
 */
public record HealthResponse(
        String status,
        String service,
        String version,
        String environment,
        Instant timestamp,
        long uptimeSeconds
) {
}
