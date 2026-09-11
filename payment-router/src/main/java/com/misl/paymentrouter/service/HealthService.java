package com.misl.paymentrouter.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.misl.paymentrouter.config.RouterProperties;
import com.misl.paymentrouter.dto.HealthResponse;

/**
 * Answers the question "is this service healthy, and what exactly is running?".
 *
 * <p>Notice what this class does NOT import: nothing from {@code jakarta.servlet},
 * nothing from {@code org.springframework.web}. It has no idea it is being called over HTTP.
 * That is the point of the service layer - it holds <i>rules</i>, not <i>transport</i>.
 * The same method could be called from a scheduled job, a CLI, or a test, unchanged.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final RouterProperties properties;
    private final Clock clock;

    /** When this instance started. Captured once, at construction. */
    private final Instant startedAt;

    /**
     * CONSTRUCTOR INJECTION.
     *
     * <p>Spring sees that {@code HealthService} is a {@code @Service} and must be created.
     * It inspects this constructor, finds it needs a {@code RouterProperties} and a
     * {@code Clock}, looks both up in the container, and passes them in. We never write
     * {@code new RouterProperties(...)} or {@code Clock.systemUTC()} here.
     *
     * <p><b>Why constructor injection rather than {@code @Autowired} on the fields?</b>
     * <ul>
     *   <li>The fields can be {@code final} - genuinely immutable after construction.</li>
     *   <li>The object is <b>impossible to construct in an invalid state</b>. With field
     *       injection you can create the object with nulls inside and only find out at the
     *       first NullPointerException.</li>
     *   <li>The constructor signature is honest documentation: these are this class's
     *       dependencies, all of them. If that list grows uncomfortably long, the class is
     *       doing too much - a design smell field injection hides.</li>
     *   <li>A unit test can do {@code new HealthService(props, fixedClock)} with no Spring
     *       at all. Field injection forces you to boot a context or use reflection.</li>
     * </ul>
     *
     * <p>Since Spring 4.3, a class with exactly one constructor needs no {@code @Autowired}
     * annotation - Spring uses it automatically. That is why there is no annotation here.
     */
    public HealthService(RouterProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.startedAt = clock.instant();
        log.info("HealthService initialised for service='{}' version='{}' environment='{}'",
                properties.serviceName(), properties.version(), properties.environment());
    }

    /**
     * Builds the current health snapshot.
     *
     * <p>Today the status is a constant. The method exists as a seam: in Step 6, "healthy"
     * will mean "and both DFSPs answered a ping", and that logic belongs here - not in a
     * controller, and not duplicated in every caller.
     */
    public HealthResponse currentHealth() {
        Instant now = clock.instant();
        long uptimeSeconds = Duration.between(startedAt, now).toSeconds();

        // Placeholder for "{}" - SLF4J only builds the string if DEBUG is actually enabled,
        // which is why we use this instead of string concatenation.
        log.debug("Health requested. uptimeSeconds={}", uptimeSeconds);

        return new HealthResponse(
                "UP",
                properties.serviceName(),
                properties.version(),
                properties.environment(),
                now,
                uptimeSeconds
        );
    }
}
