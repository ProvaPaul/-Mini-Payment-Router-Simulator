package com.misl.paymentrouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, validated-at-startup view of the {@code router.*} block in application.yml.
 *
 * <p>Spring binds YAML keys to record components by name, converting kebab-case to camelCase:
 * <pre>
 *   router:
 *     service-name: payment-router   ->  serviceName
 *     version: 0.1.0-step2           ->  version
 *     environment: local             ->  environment
 * </pre>
 *
 * <p><b>Why a class instead of sprinkling {@code @Value("${router.version}")} around?</b>
 * <ul>
 *   <li><b>One place to look.</b> Every setting this service accepts is listed here, so the
 *       configuration surface is discoverable instead of scattered across the codebase.</li>
 *   <li><b>Type safety.</b> A typo in a property name fails loudly, and values are converted
 *       to real types. {@code @Value} injects a raw String wherever you happen to write it.</li>
 *   <li><b>Testability.</b> A test can construct {@code new RouterProperties("a","b","c")}
 *       directly - no Spring context, no property files.</li>
 * </ul>
 *
 * <p><b>Why a record?</b> Java 21 records give us an immutable class with a constructor,
 * accessors, {@code equals}, {@code hashCode} and {@code toString} in one line. Spring Boot
 * supports constructor binding for records out of the box. Configuration should never change
 * while the app is running, so immutability is exactly the right guarantee here - and it is
 * why we do not need Lombok.
 *
 * <p>This record is discovered by {@code @ConfigurationPropertiesScan} on the main class.
 */
@ConfigurationProperties(prefix = "router")
public record RouterProperties(

        /** Human-readable name of this service, echoed by the health endpoint. */
        String serviceName,

        /** Build/version label, useful for confirming which build is actually deployed. */
        String version,

        /** Which environment this instance believes it is running in (local, docker, ...). */
        String environment
) {
}
