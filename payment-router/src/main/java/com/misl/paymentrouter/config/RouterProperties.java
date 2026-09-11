package com.misl.paymentrouter.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.misl.paymentrouter.model.Dfsp;
import com.misl.paymentrouter.model.FeeType;

/**
 * Typed, validated-at-startup view of the {@code router.*} block in application.yml.
 *
 * <p>Spring binds YAML keys to record components by name, converting kebab-case to camelCase
 * ({@code service-name} to {@code serviceName}), and does it recursively for nested records
 * and maps.
 *
 * <p><b>Why do the fee rules live in configuration rather than in Java?</b>
 * <ul>
 *   <li><b>Pricing changes far more often than code does.</b> A fee rate is a business
 *       parameter, not program logic. Changing 1.85% to 1.95% should not require a
 *       recompile, a code review and a redeployment of a new artifact.</li>
 *   <li><b>The same image behaves differently per environment.</b> Step 9 will run this exact
 *       jar with different environment variables. {@code ROUTER_QUOTE_ROUTERFEE=0} gives you a
 *       zero-fee test deployment with no rebuild.</li>
 *   <li><b>It makes the rules visible.</b> Anyone can read application.yml and see the entire
 *       pricing model on one screen, without reading Java.</li>
 *   <li><b>It makes tests honest.</b> {@code FeeCalculatorTest} constructs its own
 *       {@code RouterProperties} in Java, so the tests state their own inputs instead of
 *       silently depending on whatever production config happens to say today.</li>
 * </ul>
 *
 * @param serviceName human-readable name of this service, echoed by the health endpoint
 * @param version     build/version label
 * @param environment which environment this instance believes it is in
 * @param currency    ISO code all money in this deployment is denominated in
 * @param quote       settings governing quote creation
 * @param providers   per-DFSP fee rules, keyed by the {@link Dfsp} enum
 */
@ConfigurationProperties(prefix = "router")
public record RouterProperties(

        String serviceName,
        String version,
        String environment,

        /**
         * Single currency for the whole deployment. This simulator does no FX, so rather than
         * accept a currency per request and then have to reject any value but one, we fix it
         * here and report it in every response. A real router would take it per request and
         * hold a rate table.
         */
        String currency,

        QuoteSettings quote,

        Map<Dfsp, ProviderSettings> providers
) {

    /**
     * Settings that apply to every quote regardless of provider.
     *
     * @param routerFee flat switching fee this router charges, on top of the provider's fee
     * @param ttl       how long a quote stays valid. Spring parses "5m", "30s", "PT5M" into a
     *                  {@link Duration} automatically - so the YAML stays readable and we never
     *                  have to remember whether a bare number meant seconds or milliseconds.
     */
    public record QuoteSettings(
            BigDecimal routerFee,
            Duration ttl
    ) {
    }

    /**
     * One provider's pricing rules.
     *
     * <p>Fields not relevant to the chosen {@link FeeType} are simply absent from the YAML and
     * arrive as {@code null}: a FLAT provider has no percentage, and a PERCENTAGE provider need
     * not set bounds. {@code FeeCalculator} only reads the fields its branch requires, and
     * treats null bounds as "unbounded".
     *
     * @param displayName human-friendly name ("AlphaPay"); the enum constant stays the stable id
     * @param feeType     which pricing model this provider uses
     * @param percentage  percent of the amount, e.g. 1.85 means 1.85% (PERCENTAGE only)
     * @param flatFee     fixed charge per transfer (FLAT only)
     * @param minFee      floor applied after the percentage; null means no floor
     * @param maxFee      cap applied after the percentage; null means no cap
     */
    public record ProviderSettings(
            String displayName,
            FeeType feeType,
            BigDecimal percentage,
            BigDecimal flatFee,
            BigDecimal minFee,
            BigDecimal maxFee
    ) {
    }
}
