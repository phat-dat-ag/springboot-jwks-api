package com.japaneselearning.jwks.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.japaneselearning.jwks.JwksApplication;

import java.net.URI;
import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.Base64;

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
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        Path publicKey = logs.resolve("test-public.pem");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----");
        try (var context = new SpringApplicationBuilder(JwksApplication.class)
                .properties("server.port=0", "management.endpoint.health.probes.enabled=true",
                        "app.security.jwks.keys[0].kid=test-key",
                        "app.security.jwks.keys[0].public-key-path=" + publicKey,
                        "app.security.jwks.keys[0].active=true")
                .run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/actuator/health/readiness"))
                    .header("X-Correlation-ID", "integration-check")
                    .header("X-Caller-Service", "user-service")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Correlation-ID")).contains("integration-check");

            var jwks = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/.well-known/jwks.json"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(jwks.statusCode()).isEqualTo(200);
            assertThat(jwks.headers().firstValue("Cache-Control")).contains("max-age=300, public");
            var payload = new ObjectMapper().readTree(jwks.body());
            assertThat(payload.size()).isEqualTo(1);
            assertThat(payload.path("keys").size()).isEqualTo(1);
            var jwk = payload.path("keys").get(0);
            assertThat(jwk.size()).isEqualTo(6);
            assertThat(jwk.path("kid").asText()).isEqualTo("test-key");
            assertThat(jwk.path("kty").asText()).isEqualTo("RSA");
            assertThat(jwk.path("use").asText()).isEqualTo("sig");
            assertThat(jwk.path("alg").asText()).isEqualTo("RS256");
            var rsa = (RSAPublicKey) pair.getPublic();
            assertThat(new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText())))
                    .isEqualTo(rsa.getModulus());
            assertThat(new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText())))
                    .isEqualTo(rsa.getPublicExponent());

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
                assertThat(entry.path("event").asText()).isEqualTo("rsa.keys.loaded");
                assertThat(entry.path("key_count").asInt()).isEqualTo(1);
                assertThat(entry.path("active_count").asInt()).isEqualTo(1);
            });
            assertThat(entries).anySatisfy(entry -> {
                assertThat(entry.path("event").asText()).isEqualTo("application.ready");
                assertThat(entry.path("startup_ms").asLong()).isPositive();
            });
            assertThat(file).doesNotContain(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                    publicKey.toString());
            assertThat(entries).anySatisfy(entry -> {
                assertThat(entry.path("message").asText()).isEqualTo("API request completed");
                assertThat(entry.path("service").asText()).isEqualTo("jwks-service");
                assertThat(entry.path("correlationId").asText()).isEqualTo("integration-check");
                assertThat(entry.path("status").asText()).isEqualTo("200");
                assertThat(entry.path("callerService").asText()).isEqualTo("user-service");
                assertThat(entry.path("event").asText()).isEqualTo("http.request.completed");
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
