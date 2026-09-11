package com.misl.paymentrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.misl.paymentrouter.config.RouterProperties;
import com.misl.paymentrouter.config.RouterProperties.ProviderSettings;
import com.misl.paymentrouter.controller.HealthController;
import com.misl.paymentrouter.model.Dfsp;
import com.misl.paymentrouter.model.FeeType;
import com.misl.paymentrouter.service.HealthService;

/**
 * The "does it even start?" test.
 *
 * <p>{@code @SpringBootTest} boots the FULL application context exactly as
 * {@code main()} would - component scan, auto-configuration, property binding, every bean.
 * It is the slowest kind of test we will write, and the most valuable one to have exactly
 * one of: it catches the whole family of startup failures (a missing bean, a circular
 * dependency, a property that fails to bind, a malformed application.yml) at build time
 * rather than at {@code docker compose up} time.
 *
 * <p>{@code webEnvironment = MOCK} is the default: Spring MVC is configured, but no real
 * TCP port is opened. That keeps the test fast and means it cannot fail because port 8080
 * happened to be busy on the build machine.
 */
@SpringBootTest
class PaymentRouterApplicationTests {

    @Autowired
    private HealthController healthController;

    @Autowired
    private HealthService healthService;

    @Autowired
    private RouterProperties routerProperties;

    /*
     * Field injection with @Autowired is acceptable HERE, in a test, because JUnit - not our
     * code - constructs this class, so we cannot use a constructor. In production code we
     * always use constructor injection, as HealthService and HealthController do.
     */

    @Test
    @DisplayName("the Spring context starts and all expected beans exist")
    void contextLoads() {
        // If the context had failed to start, this test method would never run at all.
        // Asserting on the beans makes the test's intent explicit rather than relying on
        // an empty method body.
        assertThat(healthController).isNotNull();
        assertThat(healthService).isNotNull();
    }

    @Test
    @DisplayName("router.* properties bind from application.yml into RouterProperties")
    void routerPropertiesAreBound() {
        // Proves @ConfigurationProperties + @ConfigurationPropertiesScan actually worked.
        // A typo such as `router.servicename` in the YAML would leave this field null.
        assertThat(routerProperties.serviceName()).isEqualTo("payment-router");
        assertThat(routerProperties.version()).isNotBlank();
        assertThat(routerProperties.environment()).isEqualTo("local");
        assertThat(routerProperties.currency()).isEqualTo("BDT");
    }

    @Test
    @DisplayName("nested quote settings bind, including the Duration written as \"5m\"")
    void quoteSettingsAreBound() {
        assertThat(routerProperties.quote()).isNotNull();
        assertThat(routerProperties.quote().routerFee()).isEqualByComparingTo("5.00");
        // Spring converts the string "5m" in the YAML into a real Duration for us.
        assertThat(routerProperties.quote().ttl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("per-provider fee rules bind into a Map keyed by the Dfsp enum")
    void providerSettingsAreBound() {
        // The riskiest binding in the file: a Map with enum keys and nested records.
        // Getting it wrong leaves providers empty and every quote fails at runtime,
        // so it is worth catching here at build time.
        assertThat(routerProperties.providers()).containsOnlyKeys(Dfsp.DFSP_A, Dfsp.DFSP_B);

        ProviderSettings alphaPay = routerProperties.providers().get(Dfsp.DFSP_A);
        assertThat(alphaPay.displayName()).isEqualTo("AlphaPay");
        assertThat(alphaPay.feeType()).isEqualTo(FeeType.PERCENTAGE);
        assertThat(alphaPay.percentage()).isEqualByComparingTo("1.85");
        assertThat(alphaPay.minFee()).isEqualByComparingTo("5.00");
        assertThat(alphaPay.maxFee()).isEqualByComparingTo("50.00");

        ProviderSettings betaCash = routerProperties.providers().get(Dfsp.DFSP_B);
        assertThat(betaCash.feeType()).isEqualTo(FeeType.FLAT);
        assertThat(betaCash.flatFee()).isEqualByComparingTo("15.00");
        // Fields irrelevant to a FLAT provider are simply absent from the YAML.
        assertThat(betaCash.percentage()).isNull();
    }
}
