package com.misl.paymentrouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Payment Router service.
 *
 * <p>This class does almost nothing itself. Its value is in the two annotations
 * and in WHERE it sits: the package {@code com.misl.paymentrouter}. Component scanning
 * starts from this class's package and searches downwards, which is why every other class
 * in this project lives in a sub-package such as {@code .controller} or {@code .service}.
 * Move this class into a sub-package and Spring will stop finding your components.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentRouterApplication {

    // SLF4J is a FACADE: our code depends on this interface, never on Logback directly.
    // Swapping the logging backend later is a dependency change, not a code change.
    private static final Logger log = LoggerFactory.getLogger(PaymentRouterApplication.class);

    public static void main(String[] args) {
        // SpringApplication.run() does the real work:
        //   1. creates the ApplicationContext (the IoC container)
        //   2. scans for @Component/@Service/@RestController classes and instantiates them
        //   3. runs auto-configuration based on what is on the classpath
        //   4. starts the embedded Tomcat server on the configured port
        //   5. blocks, keeping the JVM alive until shutdown
        SpringApplication.run(PaymentRouterApplication.class, args);

        log.info("Payment Router started. Health check: http://localhost:8080/api/health");
    }
}
