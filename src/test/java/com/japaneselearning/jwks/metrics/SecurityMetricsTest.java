package com.japaneselearning.jwks.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SecurityMetricsTest {
    @Test
    void terminalClassificationIsExclusiveAndBounded() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new SecurityMetrics(registry);
            metrics.completed("POST", "/auth/login", false, 401);
            metrics.completed("POST", "/auth/refresh", false, 500);
            metrics.completed("GET", null, true, 401);
            metrics.completed("GET", "/admin/users", false, 403);
            metrics.completed("GET", "/untrusted/raw/secret", false, 200);
            assertThat(registry.getMeters()).hasSize(7);
            assertThat(registry.find("app.security.events").counters().stream().mapToDouble(c -> c.count()).sum()).isEqualTo(4);
            assertThat(registry.get("app.security.events").tags("operation", "authentication", "result", "failure").counter().count()).isZero();
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).hasSize(2));
        } finally {
            registry.close();
        }
    }
}
