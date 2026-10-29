package com.japaneselearning.jwks.common.exception;

import com.japaneselearning.jwks.common.response.APIError;
import com.japaneselearning.jwks.common.response.APIResponse;
import com.japaneselearning.jwks.logging.filter.RequestLoggingFilter;
import jakarta.validation.ConstraintViolationException;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.FieldError;

import java.util.Collections;
import java.util.IdentityHashMap;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<@NonNull Object> handleBusinessException(
            BusinessException ex,
            WebRequest request
    ) {
        return respond(
                ex,
                ex.getErrorCode(),
                null,
                new HttpHeaders(),
                ex.getErrorCode().getStatus(),
                request
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<@NonNull Object> handleConstraintViolation(
            ConstraintViolationException ex,
            WebRequest request
    ) {
        // Do not interpolate validator messages: they can contain rejected credentials or key material.
        var details = ex.getConstraintViolations().stream()
                .map(v -> invalidField(
                        v.getPropertyPath().toString()
                ))
                .toList();

        return respond(
                ex,
                ErrorCode.VALIDATION_ERROR,
                new APIError(details),
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                request
        );
    }

    @Override
    protected ResponseEntity<@NonNull Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        // Avoid the superclass warning; completion logging already records this rejection.
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    @Override
    protected ResponseEntity<@NonNull Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ErrorCode code = switch (status.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError()
                    ? ErrorCode.INTERNAL_SERVER_ERROR
                    : ErrorCode.INVALID_REQUEST;
        };

        APIError error = null;

        if (ex instanceof MethodArgumentNotValidException validation) {
            code = ErrorCode.VALIDATION_ERROR;

            error = new APIError(validation
                    .getBindingResult()
                    .getAllErrors()
                    .stream()
                    .map(e -> invalidField(e instanceof FieldError field
                            ? field.getField()
                            : null
                    )).toList()
            );

        } else if (
                ex instanceof HandlerMethodValidationException validation
                        && !validation.isForReturnValue()
        ) {
            code = ErrorCode.VALIDATION_ERROR;

            var details = new java.util.ArrayList<APIError.ErrorDetail>();

            validation.getParameterValidationResults().forEach(result ->
                    result.getResolvableErrors().forEach(e ->
                            details.add(invalidField(result.getMethodParameter().getParameterName()))
                    )
            );

            validation.getCrossParameterValidationResults()
                    .forEach(e -> details.add(invalidField(null)));

            error = new APIError(details);
        }

        return respond(ex, code, error, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<@NonNull Object> handleUnexpectedException(
            Exception ex,
            WebRequest request
    ) throws Exception {
        // Let ExceptionTranslationFilter handle security failures, including wrapped causes.
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());

        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (
                    cause instanceof AuthenticationException
                            || cause instanceof AccessDeniedException
            ) {
                throw ex;
            }
        }

        return respond(
                ex,
                ErrorCode.INTERNAL_SERVER_ERROR,
                null,
                new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                request
        );
    }

    private static APIError.ErrorDetail invalidField(String field) {
        return new APIError.ErrorDetail(field, "INVALID_FIELD", "Invalid value");
    }

    private ResponseEntity<@NonNull Object> respond(
            Exception ex,
            ErrorCode code,
            APIError error,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        if (request instanceof ServletWebRequest servlet
                && servlet.getResponse() != null
                && servlet.getResponse().isCommitted()
        ) {
            return null;
        }

        if (status.is5xxServerError()) {
            boolean recorded = request instanceof ServletWebRequest servlet
                    && RequestLoggingFilter.recordHandledFailure(servlet.getRequest(), ex);

            if (!recorded) {
                log.error("Unexpected application error", ex);
            }
        }

        // The request filter owns WARN/ERROR completion logs and safe throwable rendering.
        return new ResponseEntity<>(
                APIResponse.failure(
                        code.name(),
                        code.getMessage(),
                        error
                ),
                headers,
                status
        );
    }
}