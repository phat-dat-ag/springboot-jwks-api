package com.japaneselearning.jwks.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the shipped main configuration, including when test resources shadow it.
 */
class ObservabilityBaselineTest {
    private StandardEnvironment environment(Map<String, Object> overrides) throws Exception {
        Properties properties = new Properties();

        try (var reader = Files.newBufferedReader(
                Path.of("src/main/resources/application.properties")
        )) {
            properties.load(reader);
        }

        var environment = new StandardEnvironment();

        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("testOverrides", overrides));
        environment.getPropertySources()
                .addLast(new PropertiesPropertySource("mainConfiguration", properties));

        return environment;
    }

    @Test
    void defaultsKeepIdentityStableAndExportersOff() throws Exception {
        var environment = environment(Map.of());

        assertThat(environment.getProperty("spring.application.name")).isEqualTo("jwks-service");
        assertThat(environment.getProperty("app.deployment-environment")).isEqualTo("development");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include", "health")).isEqualTo("health");
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");

        for (String key : new String[]{
                "management.otlp.metrics.export.enabled",
                "management.tracing.export.otlp.enabled", "management.logging.export.otlp.enabled"
        }) {
            assertThat(environment.getProperty(key, Boolean.class)).as(key).isFalse();
        }

        assertThat(environment.getProperty("management.metrics.enable.all", Boolean.class)).isTrue();
        assertThat(environment.getProperty("management.tracing.enabled", Boolean.class)).isTrue();
    }

    @Test
    void environmentOverridesBindResourceAttributesWithoutDestinations() throws Exception {
        var environment = environment(Map.of(
                "SPRING_APPLICATION_NAME", "jwks-service",
                "APP_VERSION", "1.2.3",
                "DEPLOYMENT_ENVIRONMENT", "staging",
                "MANAGEMENT_METRICS_ENABLE_ALL", "false",
                "MANAGEMENT_TRACING_ENABLED", "false",
                "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", "0.25"));
        var attributes = Binder.get(environment).bind(
                "management.opentelemetry.resource-attributes",
                Bindable.mapOf(String.class, String.class)
        ).get();

        assertThat(attributes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "service.name", "jwks-service", "service.version", "1.2.3", "deployment.environment.name", "staging"
        ));
        assertThat(environment.getProperty("management.metrics.enable.all", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.tracing.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.tracing.sampling.probability", Double.class)).isEqualTo(0.25);
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")).isNull();
        assertThat(environment.getProperty("management.otlp.metrics.export.url")).isNull();
    }
}
