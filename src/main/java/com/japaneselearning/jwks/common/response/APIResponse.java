package com.japaneselearning.jwks.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record APIResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        APIError error,
        APIMeta meta
) {
    public static <T> APIResponse<T> success(
            String code,
            String message,
            T data
    ) {
        return new APIResponse<>(
                true,
                code,
                message,
                data,
                null,
                APIMeta.create()
        );
    }

    public static APIResponse<Void> failure(
            String code,
            String message,
            APIError error
    ) {
        return new APIResponse<>(
                false,
                code,
                message,
                null,
                error,
                APIMeta.create()
        );
    }
}