package com.japaneselearning.jwks.logging;

import com.japaneselearning.jwks.logging.filter.RequestLoggingFilter;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Real servlet lifecycle: error dispatch/destruction, security sendError, and MVC async workers.
 */
class RequestLoggingServletTest {
    @TempDir
    Path logs;

    @Test
    void realContainerLogsFinalSecurityErrorsAndAsyncExactlyOnce() throws Exception {
        String old = System.getProperty("APP_LOG_DIR");
        System.setProperty("APP_LOG_DIR", logs.toString());
        try (var context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
            var previous = logger.getLevel();
            logger.setLevel(Level.INFO);
            var events = Collections.synchronizedList(new ArrayList<ILoggingEvent>());
            var appender = new ListAppender<ILoggingEvent>() {
                @Override
                protected void append(ILoggingEvent event) {
                    event.prepareForDeferredProcessing();
                    events.add(event);
                }
            };
            appender.start();
            logger.addAppender(appender);
            try {
                var client = HttpClient.newHttpClient();
                String base = "http://127.0.0.1:" + port;
                send(client, base + "/probe/secret-path?password=query-secret", "sync-id", null, 200);
                send(client, base + "/restricted", "unauth-id", null, 401);
                String basic = "Basic " + Base64.getEncoder().encodeToString("phase1-test:fixture-password".getBytes());
                send(client, base + "/restricted", "forbidden-id", basic, 403);
                send(client, base + "/escape-probe", "error-id", null, 500);
                var async = send(client, base + "/async-probe", "async-id", null, 200);
                assertThat(async.body()).isEqualTo("async-id");
                for (int i = 0; i < 200 && events.size() < 5; i++) Thread.sleep(10);
                assertThat(events).hasSize(5);
                var encoder = new StructuredJsonEncoder();
                encoder.setContext(logger.getLoggerContext());
                encoder.start();
                try {
                    var mapper = new ObjectMapper();
                    var records = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                    synchronized (events) {
                        for (var event : events) {
                            var node = mapper.readTree(encoder.encode(event));
                            records.put(node.path("correlationId").asText(), node);
                        }
                    }
                    assertThat(records).hasSize(5);
                    assertThat(records.get("sync-id").path("http.route").asText()).isEqualTo("/probe/{id}");
                    assertThat(records.get("unauth-id").path("http.response.status_code").asInt()).isEqualTo(401);
                    assertThat(records.get("forbidden-id").path("http.response.status_code").asInt()).isEqualTo(403);
                    assertThat(records.get("error-id").path("http.response.status_code").asInt()).isEqualTo(500);
                    assertThat(records.get("error-id").path("http.route").asText()).isEqualTo("/escape-probe");
                    assertThat(records.get("async-id").path("http.route").asText()).isEqualTo("/async-probe");
                    assertThat(records.toString()).doesNotContain("secret-path", "query-secret", "exception-message-secret");
                } finally {
                    encoder.stop();
                }
            } finally {
                logger.detachAppender(appender);
                appender.stop();
                logger.setLevel(previous);
            }
        } finally {
            ((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory()).stop();
            if (old == null) System.clearProperty("APP_LOG_DIR");
            else System.setProperty("APP_LOG_DIR", old);
        }
    }

    private HttpResponse<String> send(HttpClient client, String uri, String correlation, String auth, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(uri)).header("X-Correlation-ID", correlation);
        if (auth != null) builder.header("Authorization", auth);
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Correlation-ID")).contains(correlation);
        return response;
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({RequestLoggingFilter.class, RequestLoggingConfiguration.class, ProbeController.class})
    static class TestConfiguration {
        @Bean
        SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.requestMatchers("/restricted").hasRole("ADMIN").anyRequest().permitAll())
                    .httpBasic(withDefaults()).build();
        }

        @Bean
        UserDetailsService testUsers() {
            return new InMemoryUserDetailsManager(User.withUsername("phase1-test").password("{noop}fixture-password").roles("USER").build());
        }
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class ProbeController {
        @GetMapping("/probe/{id}")
        String ok(@PathVariable String id) {
            return "ok";
        }

        @GetMapping("/restricted")
        String restricted() {
            return "ok";
        }

        @GetMapping("/escape-probe")
        String error() {
            throw new IllegalStateException("exception-message-secret");
        }

        @GetMapping("/async-probe")
        Callable<String> async() {
            return () -> MDC.get("correlationId");
        }
    }
}
