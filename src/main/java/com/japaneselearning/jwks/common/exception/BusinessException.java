package com.japaneselearning.jwks.common.exception;

import lombok.Getter;

import java.util.Objects;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(
                Objects.requireNonNull(errorCode).getMessage(),
                cause
        );

        this.errorCode = errorCode;
    }
}