package com.japaneselearning.jwks.metrics;

import com.japaneselearning.jwks.logging.RequestLoggingConfiguration;
import com.japaneselearning.jwks.logging.filter.RequestLoggingFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Exercises both real connector ports, security, observations, histograms and terminal counters.
 */
class MetricsIntegrationTest {
    @TempDir
    Path logs;

    @Test
    void privateScrapeOnlyAndBoundedFrameworkMetrics() throws Exception {
        String previous = System.getProperty("APP_LOG_DIR");
        System.setProperty("APP_LOG_DIR", logs.toString());
        try (var context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.SERVLET)
                .run("--spring.config.location=file:src/main/resources/application.properties", "--server.port=0", "--app.metrics.listener.enabled=true", "--app.metrics.listener.port=0",
                        "--management.endpoints.web.exposure.include=health,prometheus",
                        "--management.endpoint.health.probes.enabled=true",
                        "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration")) {
            int appPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            int scrapePort = context.getBean(Connector.class).getLocalPort();
            assertThat(scrapePort).isPositive().isNotEqualTo(appPort);
            var registry = context.getBean(MeterRegistry.class);
            var client = HttpClient.newHttpClient();
            String basic = "Basic " + Base64.getEncoder().encodeToString("metrics-fixture:fixture-password".getBytes());
            send(client, appPort, "/actuator/health/readiness", null, false, 200);
            send(client, appPort, "/actuator/prometheus", null, false, 403);
            send(client, appPort, "/actuator/prometheus", basic, false, 403);
            send(client, scrapePort, "/actuator/env", null, false, 403);
            send(client, scrapePort, "/actuator/health", null, false, 403);
            send(client, scrapePort, "/probe/secret-one?password=query-secret", null, false, 403);
            send(client, appPort, "/probe/secret-one?password=query-secret", null, false, 200);
            send(client, appPort, "/probe/secret-two", null, false, 200);
            send(client, appPort, "/restricted", null, false, 401);
            send(client, appPort, "/restricted", basic, false, 403);
            send(client, appPort, "/async-probe", null, false, 200);
            send(client, appPort, "/failed-probe", null, false, 500);
            send(client, appPort, "/auth/login", null, true, 200);
            send(client, appPort, "/auth/refresh", null, true, 401);
            var scrape = send(client, scrapePort, "/actuator/prometheus", null, false, 200).body();
            assertThat(scrape).contains("jvm_memory_used_bytes", "jvm_threads_live_threads", "process_uptime_seconds",
                    "app_build_info", "http_server_requests_seconds_bucket", "app_security_events_total",
                    "uri=\"/probe/{id}\"", "le=\"0.1\"");
            assertThat(scrape).doesNotContain("secret-one", "secret-two", "query-secret", "private-exception-secret",
                    "metrics-correlation-secret", "metrics-fixture", "fixture-password", "correlationId", "traceId");
            for (int i = 0; i < 100 && registry.find("http.server.requests").tag("uri", "/probe/{id}")
                    .timers().stream().mapToLong(Timer::count).sum() < 2; i++)
                Thread.sleep(10);
            assertThat(registry.find("http.server.requests").tag("uri", "/probe/{id}")
                    .timers().stream().mapToLong(Timer::count).sum()).isEqualTo(2);
            assertThat(registry.find("http.server.requests").tags("uri", "/async-probe", "status", "200").timer().count()).isEqualTo(1);
            assertThat(registry.get("app.security.events").tags("operation", "login", "result", "success").counter().count()).isEqualTo(1);
            assertThat(registry.get("app.security.events").tags("operation", "refresh", "result", "failure").counter().count()).isEqualTo(1);
            assertThat(registry.find("http.server.requests").tags("uri", "/failed-probe", "status", "500").timer().count()).isEqualTo(1);
            assertThat(registry.find("http.server.requests").timers()).allSatisfy(timer -> {
                assertThat(timer.getId().getTag("service")).isNotBlank();
                assertThat(timer.getId().getTag("environment")).isEqualTo("development");
                assertThat(timer.getId().getTag("version")).isNull();
            });
        } finally {
            ((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).stop();
            if (previous == null) System.clearProperty("APP_LOG_DIR");
            else System.setProperty("APP_LOG_DIR", previous);
        }
    }

    private HttpResponse<String> send(HttpClient client, int port, String path, String auth, boolean post, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Correlation-ID", "metrics-correlation-secret");
        if (auth != null) builder.header("Authorization", auth);
        if (post) builder.POST(HttpRequest.BodyPublishers.noBody());
        var result = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).as(path).isEqualTo(status);
        return result;
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({MetricsConfiguration.class, MetricsEndpointConfiguration.class,
            RequestLoggingFilter.class, RequestLoggingConfiguration.class, Probe.class})
    static class Fixture {
        @Bean
        @Order(10)
        SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(a -> a
                            .requestMatchers("/restricted").hasRole("ADMIN").anyRequest().permitAll())
                    .httpBasic(withDefaults()).build();
        }

        @Bean
        UserDetailsService fixtureUsers() {
            return new InMemoryUserDetailsManager(User.withUsername("metrics-fixture")
                    .password("{noop}fixture-password").roles("USER").build());
        }
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class Probe {
        @GetMapping("/probe/{id}")
        String ok(@PathVariable String id) {
            return "ok";
        }

        @GetMapping("/restricted")
        String restricted() {
            return "ok";
        }

        @GetMapping("/async-probe")
        Callable<String> async() {
            return () -> "ok";
        }

        @GetMapping("/failed-probe")
        String failure() {
            throw new IllegalStateException("private-exception-secret");
        }

        @PostMapping("/auth/login")
        String login() {
            return "ok";
        }

        @PostMapping("/auth/refresh")
        void refresh(jakarta.servlet.http.HttpServletResponse response) {
            response.setStatus(401);
        }
    }
}
