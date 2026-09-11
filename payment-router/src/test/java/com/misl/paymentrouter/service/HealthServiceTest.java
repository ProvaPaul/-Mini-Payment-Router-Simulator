package com.misl.paymentrouter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.misl.paymentrouter.config.RouterProperties;
import com.misl.paymentrouter.dto.HealthResponse;

/**
 * A pure unit test: no Spring, no annotations, no application context, no HTTP.
 * It runs in milliseconds because it only constructs plain Java objects.
 *
 * <p>This file is the concrete proof of why the architecture is shaped the way it is.
 * {@link HealthService} could be tested this way ONLY because:
 * <ul>
 *   <li>it knows nothing about HTTP (so we need no server), and</li>
 *   <li>it receives its dependencies through its constructor (so we can substitute them).</li>
 * </ul>
 * Put this same logic inside the controller and this test becomes impossible - you would be
 * forced to boot Spring MVC just to check an uptime calculation.
 */
class HealthServiceTest {

    private static final RouterProperties PROPS =
            new RouterProperties("payment-router", "0.1.0-step2", "test");

    @Test
    @DisplayName("reports UP and echoes the configured service identity")
    void reportsConfiguredIdentity() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-11T20:00:00Z"), ZoneOffset.UTC);

        HealthResponse response = new HealthService(PROPS, fixed).currentHealth();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.service()).isEqualTo("payment-router");
        assertThat(response.version()).isEqualTo("0.1.0-step2");
        assertThat(response.environment()).isEqualTo("test");
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-09-11T20:00:00Z"));
    }

    @Test
    @DisplayName("uptime is measured from construction, using the injected clock")
    void uptimeIsMeasuredFromStartup() {
        // A mutable clock: starts at T, and we shove it forward on demand.
        // This is the whole reason AppConfig injects a Clock instead of the service
        // calling Instant.now(). We advance time by 90 seconds WITHOUT waiting 90 seconds.
        Instant start = Instant.parse("2026-09-11T20:00:00Z");
        MutableClock clock = new MutableClock(start);

        HealthService service = new HealthService(PROPS, clock);
        assertThat(service.currentHealth().uptimeSeconds()).isZero();

        clock.advance(Duration.ofSeconds(90));

        assertThat(service.currentHealth().uptimeSeconds()).isEqualTo(90L);
    }

    /** Minimal test double for java.time.Clock whose "now" we control. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            this.now = this.now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
