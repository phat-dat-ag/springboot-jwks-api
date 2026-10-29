package com.japaneselearning.jwks.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record APIError(List<ErrorDetail> details) {
    public APIError {
        details = List.copyOf(details);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String field,
            String code,
            String message
    ) {
    }
}