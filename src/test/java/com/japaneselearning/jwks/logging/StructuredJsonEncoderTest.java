package com.japaneselearning.jwks.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class StructuredJsonEncoderTest {
    @Test
    void allowlistsContextAndKeyValuesAndNeverEmitsDuplicateMetadataOrExceptionMessages() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("phase1.encoder.contract");
        Level previous = logger.getLevel();
        boolean additive = logger.isAdditive();
        List<ILoggingEvent> events = new ArrayList<>();
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        appender.start();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
        var encoder = new StructuredJsonEncoder();
        encoder.setContext(logger.getLoggerContext());
        encoder.setServiceName("jwks-service");
        encoder.setServiceVersion("1.2.3");
        encoder.setEnvironment("test");
        encoder.start();
        try {
            MDC.put("correlationId", "12345");
            MDC.put("password", "mdc-password-secret");
            MDC.put("Authorization", "Bearer authorization-secret");
            logger.atInfo().addKeyValue("event", "schema.probe").addKeyValue("http.response.status_code", 200)
                    .addKeyValue("durationMs", 12L).addKeyValue("password", "kv-password-secret")
                    .addKeyValue("service", "forged-service").log("Safe operation completed");
            var failure = new IllegalStateException("username=exception-secret");
            failure.addSuppressed(new IllegalArgumentException("token=suppressed-secret"));
            logger.error("password=message-secret", failure);
            var mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
            var normal = mapper.readTree(encoder.encode(events.get(0)));
            assertThat(normal.path("service").asText()).isEqualTo("jwks-service");
            assertThat(normal.path("serviceVersion").asText()).isEqualTo("1.2.3");
            assertThat(normal.path("environment").asText()).isEqualTo("test");
            assertThat(normal.path("correlationId").isTextual()).isTrue();
            assertThat(normal.path("http.response.status_code").isIntegralNumber()).isTrue();
            assertThat(normal.path("durationMs").isNumber()).isTrue();
            assertThat(normal.path("@timestamp").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
            var databaseEvent = new ch.qos.logback.classic.spi.LoggingEvent();
            databaseEvent.setTimeStamp(System.currentTimeMillis());
            databaseEvent.setThreadName("test");
            databaseEvent.setMDCPropertyMap(Map.of());
            databaseEvent.setLoggerName("org.hibernate.engine.jdbc.spi.SqlExceptionHelper");
            databaseEvent.setLevel(Level.ERROR);
            databaseEvent.setMessage("Duplicate entry private-email-secret@example.invalid");
            var database = mapper.readTree(encoder.encode(databaseEvent));
            assertThat(database.toString()).doesNotContain("private-email-secret");
            var error = mapper.readTree(encoder.encode(events.get(1)));
            assertThat(error.path("stack_trace").asText()).contains("IllegalStateException", "IllegalArgumentException", "Suppressed:");
            assertThat(normal.toString() + error).doesNotContain("mdc-password-secret", "authorization-secret", "kv-password-secret",
                    "forged-service", "exception-secret", "suppressed-secret", "message-secret");
        } finally {
            encoder.stop();
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
            logger.setAdditive(additive);
            MDC.clear();
        }
    }

    @Test
    void unsafeIdentityConfigurationCannotInjectJsonOrSecretValues() throws Exception {
        var encoder = new StructuredJsonEncoder();
        encoder.setContext(((Logger) LoggerFactory.getLogger("phase1.metadata")).getLoggerContext());
        encoder.setServiceName("bad\"service-secret");
        encoder.setServiceVersion("bad\nversion-secret");
        encoder.setEnvironment("x".repeat(65));
        encoder.start();
        try {
            var event = new ch.qos.logback.classic.spi.LoggingEvent();
            event.setTimeStamp(System.currentTimeMillis());
            event.setLevel(Level.INFO);
            event.setLoggerName("phase1.metadata");
            event.setThreadName("test");
            event.setMessage("Safe metadata probe");
            event.setMDCPropertyMap(Map.of());
            var record = new ObjectMapper().readTree(encoder.encode(event));
            assertThat(record.path("service").asText()).isEqualTo("unknown");
            assertThat(record.path("serviceVersion").asText()).isEqualTo("unknown");
            assertThat(record.path("environment").asText()).isEqualTo("unknown");
            assertThat(record.toString()).doesNotContain("service-secret", "version-secret");
        } finally {
            encoder.stop();
        }
    }
}
