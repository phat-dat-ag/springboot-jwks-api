package com.japaneselearning.jwks.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record APIMeta(
        Instant timestamp,
        String correlationId,
        String traceId
) {

    public static APIMeta create() {
        return new APIMeta(
                Instant.now(),
                MDC.get("correlationId"),
                MDC.get("traceId")
        );
    }
}