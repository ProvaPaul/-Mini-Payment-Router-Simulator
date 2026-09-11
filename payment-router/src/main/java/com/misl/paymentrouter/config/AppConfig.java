package com.misl.paymentrouter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-wide beans that are not components we wrote ourselves.
 *
 * <p>{@code @Configuration} marks a class as a source of bean definitions.
 * {@code @Bean} marks a method whose RETURN VALUE Spring should manage. Use this when you need
 * an object from a library you do not own, so you cannot annotate its class with
 * {@code @Service}. {@link Clock} is from the JDK - we cannot annotate it, so we produce it here.
 */
@Configuration
public class AppConfig {

    /**
     * The application's source of "what time is it?".
     *
     * <p><b>Why inject a Clock instead of calling {@code Instant.now()} directly?</b>
     * This is the single clearest demonstration of why dependency injection exists.
     *
     * <p>{@code Instant.now()} is a hidden, hard-wired dependency on the real system clock.
     * Code that calls it cannot be tested for time-dependent behaviour: to test that a quote
     * expires after five minutes you would have to make the test actually sleep for five minutes.
     *
     * <p>By injecting a {@code Clock}, production code gets {@code Clock.systemUTC()} while a
     * test can pass {@code Clock.fixed(...)} and control time exactly. Nothing in the business
     * code changes. In Step 3 our quotes get a five-minute expiry, and in Step 7 we will test
     * that expiry instantly - which is only possible because of this one bean.
     *
     * <p>UTC, not the system default zone, so that timestamps mean the same thing on a laptop
     * in Dhaka and inside a Docker container running in UTC.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
