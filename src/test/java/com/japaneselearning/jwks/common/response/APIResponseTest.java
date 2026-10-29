package com.japaneselearning.jwks.common.response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class APIResponseTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void successIncludesGenericPayloadAndExistingMetadata() {
        MDC.put("correlationId", "request-123");
        MDC.put("traceId", "trace-123");
        Instant before = Instant.now();
        var response = APIResponse.success(null, null, Map.of("ready", true));
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(response.meta().timestamp()).isBetween(before, Instant.now());
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("data").path("ready").asBoolean()).isTrue();
        assertThat(json.path("meta").path("correlationId").asString()).isEqualTo("request-123");
        assertThat(json.path("meta").path("traceId").asString()).isEqualTo("trace-123");
        assertThat(json.path("meta").path("timestamp").isTextual()).isTrue();
        assertThat(json.has("error")).isFalse();
        assertThat(json.has("code")).isFalse();
        assertThat(json.has("message")).isFalse();
    }

    @Test
    void failureOmitsNullsAndUsesReferenceDetailStructure() {
        var response = APIResponse.failure("VALIDATION_ERROR", "Request validation failed",
                new APIError(List.of(new APIError.ErrorDetail("name", "INVALID_FIELD", "Invalid value"))));
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.has("data")).isFalse();
        assertThat(json.path("meta").has("timestamp")).isTrue();
        assertThat(json.path("meta").has("correlationId")).isFalse();
        assertThat(json.path("meta").has("traceId")).isFalse();
        assertThat(json.path("error").path("details").get(0).path("field").asString()).isEqualTo("name");
        assertThat(mapper.writeValueAsString(APIResponse.failure("INVALID_REQUEST", "Invalid request", null)))
                .doesNotContain("\"error\"", "\"data\"");
    }
}