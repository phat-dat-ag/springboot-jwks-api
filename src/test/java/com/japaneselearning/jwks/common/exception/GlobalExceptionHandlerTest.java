package com.japaneselearning.jwks.common.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.japaneselearning.jwks.common.response.APIResponse;
import com.japaneselearning.jwks.logging.filter.RequestLoggingFilter;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;
    private final GlobalExceptionHandler advice = new GlobalExceptionHandler();

    @BeforeEach
    void setup() {
        mvc = standaloneSetup(new TestController()).setControllerAdvice(advice)
                .addFilters(new RequestLoggingFilter()).build();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void businessStatusesAndSafeMessages(ErrorCode code) throws Exception {
        var cause = new IllegalStateException("secret");
        var exception = new BusinessException(code, cause);
        assertThat(exception.getCause()).isSameAs(cause);
        mvc.perform(get("/business").param("code", code.name()))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void validatesBodyWithoutLeakingInterpolatedValues() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"))
                .andExpect(jsonPath("$.error.details[0].code").value("INVALID_FIELD"))
                .andExpect(jsonPath("$.error.details[0].message").value("Invalid value"));
    }

    @Test
    void validatesMethodParameters() throws Exception {
        mvc.perform(get("/number").param("number", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("number"));
    }

    @Test
    void handlesConstraintViolations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new Payload(""));
            var result = advice.handleConstraintViolation(new ConstraintViolationException(violations),
                    new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()));
            assertThat(result.getStatusCode().value()).isEqualTo(400);
            var body = (APIResponse<?>) result.getBody();
            assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(body.error().details().get(0).message()).isEqualTo("Invalid value");
        }
    }

    @Test
    void handlesMissingAndInvalidParameters() throws Exception {
        mvc.perform(get("/number")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/number").param("number", "secret")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void handlesMalformedAndMissingJson() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void preservesMethodAndMediaTypeStatusesAndHeaders() throws Exception {
        mvc.perform(put("/body")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("secret"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/success").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void handlesMissingRoutesAndStaticResources() throws Exception {
        mvc.perform(get("/absent")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/resource")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unexpectedFailureHasSafeBodyAndOneDiagnosticLog() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            MDC.put("traceId", "trace-123");
            mvc.perform(get("/unexpected").header("X-Correlation-ID", "request-123"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().string("X-Correlation-ID", "request-123"))
                    .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                    .andExpect(jsonPath("$.meta.correlationId").value("request-123"))
                    .andExpect(jsonPath("$.meta.traceId").value("trace-123"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
            assertThat(appender.list.get(0).getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void securityExceptionsEscapeAdviceIncludingWrappedCauses() {
        for (Exception error : List.of(new AccessDeniedException("denied"),
                new BadCredentialsException("bad"), new RuntimeException(new AccessDeniedException("denied")))) {
            assertThatThrownBy(() -> advice.handleUnexpectedException(error,
                    new ServletWebRequest(new MockHttpServletRequest()))).isSameAs(error);
        }
    }

    @Test
    void successfulResponsesAreOnlyExplicitlyWrapped() throws Exception {
        mvc.perform(get("/success")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.ready").value(true));
        // Test-only raw payload: no production JWKS endpoint exists in this service yet.
        mvc.perform(get("/raw")).andExpect(status().isOk())
                .andExpect(content().json("{\"keys\":[]}"))
                .andExpect(jsonPath("$.meta").doesNotExist()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void returnValueValidationIsAnInternalError() throws Exception {
        mvc.perform(get("/invalid-return")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void committedResponseIsNotRewritten() throws Exception {
        var response = new MockHttpServletResponse();
        response.flushBuffer();
        assertThat(advice.handleUnexpectedException(new IllegalStateException(),
                new ServletWebRequest(new MockHttpServletRequest(), response))).isNull();
    }

    @Test
    void securityFilterStillTranslatesControllerFailures() throws Exception {
        var security = new org.springframework.security.web.access.ExceptionTranslationFilter(
                (request, response, exception) -> response.setStatus(401));
        security.setAccessDeniedHandler((request, response, exception) -> response.setStatus(403));
        var securedMvc = standaloneSetup(new TestController()).setControllerAdvice(advice)
                .addFilters(new RequestLoggingFilter(), security).build();
        try {
            var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "test-user", null, List.of());
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(authentication);
            securedMvc.perform(get("/denied")).andExpect(status().isForbidden())
                    .andExpect(content().string(""));
            securedMvc.perform(get("/unauthenticated")).andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/invalid-return")
        @NotBlank
        String invalidReturn() {
            return "";
        }

        @GetMapping("/denied")
        Object denied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/unauthenticated")
        Object unauthenticated() {
            throw new BadCredentialsException("bad");
        }

        @GetMapping("/business")
        Object business(@RequestParam ErrorCode code) {
            throw new BusinessException(code);
        }

        @PostMapping(value = "/body", consumes = "application/json")
        Object body(@Valid @RequestBody Payload payload) {
            return payload;
        }

        @GetMapping("/number")
        Object number(@RequestParam @Min(1) int number) {
            return number;
        }

        @GetMapping("/unexpected")
        Object unexpected() {
            throw new IllegalStateException("secret private key");
        }

        @GetMapping("/resource")
        Object resource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "secret-path", "secret-path");
        }

        @GetMapping(value = "/success", produces = "application/json")
        Object success() {
            return APIResponse.success(null, null, Map.of("ready", true));
        }

        @GetMapping("/raw")
        Object raw() {
            return Map.of("keys", List.of());
        }
    }

    record Payload(@NotBlank(message = "secret validator message") String name) {
    }
}