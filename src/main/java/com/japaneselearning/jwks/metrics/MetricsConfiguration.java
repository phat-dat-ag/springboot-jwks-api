package com.japaneselearning.jwks.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared low-cardinality identity; version belongs on one info meter, not every series.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {
    @Bean
    MeterRegistryCustomizer<MeterRegistry> metricsIdentity(
            @Value("${spring.application.name}")
            String service,

            @Value("${app.deployment-environment:development}")
            String environment,

            @Value("${app.version:unknown}")
            String version
    ) {
        return registry -> {
            registry.config().commonTags("service", safe(service), "environment", safe(environment));

            Gauge.builder("app.build.info", () -> 1)
                    .description("Application build identity")
                    .tag("version", safe(version)).register(registry);
        };
    }

    @Bean
    MeterFilter serverRouteBudget() {
        return MeterFilter.maximumAllowableTags(
                "http.server.requests",
                "uri",
                100,
                MeterFilter.deny()
        );
    }

    @Bean
    MeterFilter clientRouteBudget() {
        return MeterFilter.maximumAllowableTags(
                "http.client.requests",
                "uri",
                100,
                MeterFilter.deny()
        );
    }

    @Bean
    SecurityMetrics securityMetrics(MeterRegistry registry) {
        return new SecurityMetrics(registry);
    }

    private static String safe(String value) {
        return value != null
                && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                ? value
                : "unknown";
    }
}
