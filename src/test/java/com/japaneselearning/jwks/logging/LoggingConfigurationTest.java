package com.japaneselearning.jwks.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.japaneselearning.jwks.JwksApplication;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class LoggingConfigurationTest {
    @TempDir
    Path logs;

    @Test
    void loadsJsonConsoleAndFileLoggingWithRotationPolicy(CapturedOutput output) throws Exception {
        String previous = System.getProperty("APP_LOG_DIR");
        System.setProperty("APP_LOG_DIR", logs.toString());
        try (var context = new SpringApplicationBuilder(JwksApplication.class)
                .properties("server.port=0", "management.endpoint.health.probes.enabled=true")
                .run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/actuator/health/readiness"))
                    .header("X-Correlation-ID", "integration-check")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Correlation-ID")).contains("integration-check");

            var root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
            var appender = (RollingFileAppender<?>) root.getAppender("FILE");
            assertThat(appender.isStarted()).isTrue();
            var policy = (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
            assertThat(policy.isStarted()).isTrue();
            assertThat(policy.getMaxHistory()).isEqualTo(30);
            assertThat(policy.isCleanHistoryOnStart()).isTrue();
            assertThat(policy.getFileNamePattern()).endsWith("archive/jwks-api.%d{yyyy-MM-dd}.%i.log.gz");

            var mapper = new ObjectMapper();
            // Request completion is logged after the HTTP response may have reached the client.
            String file = "";
            for (int i = 0; i < 100; i++) {
                file = Files.readString(logs.resolve("jwks-api.log"));
                if (file.contains("API request completed")) break;
                Thread.sleep(20);
            }
            var entries = file.lines().map(line -> {
                try {
                    return mapper.readTree(line);
                } catch (Exception ex) {
                    throw new AssertionError("Invalid JSON log line", ex);
                }
            }).toList();
            assertThat(entries).anySatisfy(entry -> {
                assertThat(entry.path("message").asText()).isEqualTo("API request completed");
                assertThat(entry.path("service").asText()).isEqualTo("jwks-service");
                assertThat(entry.path("correlationId").asText()).isEqualTo("integration-check");
                assertThat(entry.path("status").asText()).isEqualTo("200");
            });
            assertThat(output.getOut()).contains("\"service\":\"jwks-service\"", "\"correlationId\":\"integration-check\"")
                    .doesNotContain("Using generated security password");
            for (String line : output.getOut().lines().filter(line -> line.startsWith("{")).toList()) {
                assertThat(mapper.readTree(line).has("@timestamp")).isTrue();
            }
        } finally {
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
            if (previous == null) System.clearProperty("APP_LOG_DIR");
            else System.setProperty("APP_LOG_DIR", previous);
        }
    }
}