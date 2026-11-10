package com.japaneselearning.jwks.logging.filter;

import com.japaneselearning.jwks.logging.StructuredJsonEncoder;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;

import java.util.*;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class RequestLoggingFilterTest {
    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final List<ILoggingEvent> events = new ArrayList<>();
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;
    private boolean previousAdditive;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MDC.clear();
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        request = new MockHttpServletRequest("GET", "/keys/raw-secret");
        response = new MockHttpServletResponse();
        appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
        appender.stop();
        MDC.clear();
    }

    private JsonNode json(ILoggingEvent event) throws Exception {
        var encoder = new StructuredJsonEncoder();
        encoder.setContext(logger.getLoggerContext());
        encoder.start();
        try {
            return new ObjectMapper().readTree(encoder.encode(event));
        } finally {
            encoder.stop();
        }
    }

    @Test
    void missingIdIsGeneratedAndReturnedAndNumericFieldsUseSafeRoute() throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("correlationId")).isEqualTo(response.getHeader("X-Correlation-ID"));
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/keys/{kid}");
            response.setStatus(201);
        });
        UUID.fromString(response.getHeader("X-Correlation-ID"));
        assertThat(events).hasSize(1);
        var record = json(events.get(0));
        assertThat(record.path("http.request.method").asText()).isEqualTo("GET");
        assertThat(record.path("http.route").asText()).isEqualTo("/keys/{kid}");
        assertThat(record.path("http.response.status_code").isIntegralNumber()).isTrue();
        assertThat(record.path("http.response.status_code").asInt()).isEqualTo(201);
        assertThat(record.path("durationMs").isNumber()).isTrue();
        assertThat(record.path("durationMs").asLong()).isNotNegative();
        assertThat(record.path("outcome").asText()).isEqualTo("success");
        assertThat(record.has("status") || record.has("elapsed_ms") || record.has("uri")).isFalse();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"request-123_abc.4", "12345", "A"})
    void acceptsValidatedIncomingIds(String id) throws Exception {
        request.addHeader("X-Correlation-ID", id);
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("correlationId")).isEqualTo(id));
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo(id);
        assertThat(json(events.get(0)).path("correlationId").asText()).isEqualTo(id);
    }

    @Test
    void acceptsMaximumLengthId() throws Exception {
        request.addHeader("X-Correlation-ID", "a".repeat(64));
        filter.doFilter(request, response, (a, b) -> {
        });
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("a".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "space value", "bad\r\nheader", "/bad", "a,b"})
    void replacesUnsafeIdWithoutLoggingIt(String incoming) throws Exception {
        request.addHeader("X-Correlation-ID", incoming);
        filter.doFilter(request, response, (a, b) -> {
        });
        UUID.fromString(response.getHeader("X-Correlation-ID"));
        assertThat(response.getHeader("X-Correlation-ID")).isNotEqualTo(incoming);
    }

    @Test
    void replacesOversizedAndDuplicateHeaders() throws Exception {
        request.addHeader("X-Correlation-ID", "x".repeat(65));
        filter.doFilter(request, response, (a, b) -> {
        });
        UUID.fromString(response.getHeader("X-Correlation-ID"));
        var next = new MockHttpServletRequest("GET", "/");
        var res = new MockHttpServletResponse();
        next.addHeader("X-Correlation-ID", "one");
        next.addHeader("X-Correlation-ID", "two");
        filter.doFilter(next, res, (a, b) -> {
        });
        UUID.fromString(res.getHeader("X-Correlation-ID"));
    }

    @Test
    void clearsStaleRequestFieldsOnThreadReuseButRestoresOuterTraceContext() throws Exception {
        MDC.put("correlationId", "stale");
        MDC.put("uri", "old-secret");
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");
        MDC.put("spanId", "0123456789abcdef");
        filter.doFilter(request, response, (a, b) -> {
        });
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("uri")).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(json(events.get(0)).path("spanId").asText()).isEqualTo("0123456789abcdef");
        MDC.clear();
        var next = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/"), next, (a, b) -> {
        });
        assertThat(next.getHeader("X-Correlation-ID")).isNotEqualTo(response.getHeader("X-Correlation-ID"));
        assertThat(json(events.get(1)).has("traceId")).isFalse();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
    void logsFinalStatusAndSeverity(int status) throws Exception {
        filter.doFilter(request, response, (a, b) -> response.setStatus(status));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(status >= 500 ? Level.ERROR : Level.WARN);
        var record = json(events.get(0));
        assertThat(record.path("http.response.status_code").asInt()).isEqualTo(status);
        assertThat(record.path("outcome").asText()).isEqualTo(status >= 500 ? "server_error" : "client_error");
        assertThat(record.path("error.category").asText()).isEqualTo(status == 401 ? "authentication" : status == 403 ? "authorization" : status >= 500 ? "server" : "client");
    }

    @Test
    void handledFailureRendersOnlySafeExceptionDetailsOnce() throws Exception {
        var failure = new IllegalStateException("password=exception-secret", new RuntimeException("private-key-secret"));
        filter.doFilter(request, response, (a, b) -> {
            RequestLoggingFilter.recordHandledFailure(request, failure);
            RequestLoggingFilter.recordErrorCode(request, "INTERNAL_SERVER_ERROR");
            response.setStatus(500);
        });
        RequestLoggingFilter.completeRequest(request);
        assertThat(events).hasSize(1);
        var record = json(events.get(0));
        assertThat(record.path("error.type").asText()).isEqualTo(IllegalStateException.class.getName());
        assertThat(record.path("errorCode").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(record.path("stack_trace").asText()).contains("IllegalStateException", "RuntimeException", "Caused by:");
        assertThat(record.toString()).doesNotContain("exception-secret", "private-key-secret");
    }

    @Test
    void escapedExceptionWaitsForErrorDispatchAndLogsOriginalTemplate() throws Exception {
        var error = new ServletException("secret");
        assertThatThrownBy(() -> filter.doFilter(request, response, (a, b) -> {
            a.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/keys/{kid}");
            throw error;
        })).isSameAs(error);
        assertThat(events).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        request.setDispatcherType(DispatcherType.ERROR);
        response.setStatus(500);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/error");
        filter.doFilter(request, response, (a, b) -> {
        });
        RequestLoggingFilter.completeRequest(request);
        assertThat(events).hasSize(1);
        assertThat(json(events.get(0)).path("http.route").asText()).isEqualTo("/keys/{kid}");
    }

    @Test
    void servletDestructionCompletesUnhandledFailureWhenThereIsNoErrorDispatch() throws Exception {
        assertThatThrownBy(() -> filter.doFilter(request, response, (a, b) -> {
            throw new ServletException("secret");
        }));
        assertThat(events).isEmpty();
        response.setStatus(503);
        RequestLoggingFilter.completeRequest(request);
        RequestLoggingFilter.completeRequest(request);
        assertThat(events).hasSize(1);
        assertThat(json(events.get(0)).path("http.response.status_code").asInt()).isEqualTo(503);
    }

    @Test
    void sendErrorDoesNotPrematurelyRecordStatusBeforeContainerErrorHandling() throws Exception {
        filter.doFilter(request, response, (a, b) -> ((jakarta.servlet.http.HttpServletResponse) b).sendError(401, "secret"));
        assertThat(events).isEmpty();
        RequestLoggingFilter.completeRequest(request);
        assertThat(events).hasSize(1);
        assertThat(json(events.get(0)).path("http.response.status_code").asInt()).isEqualTo(401);
    }

    @Test
    void asyncCompletionIsOnceAndRestoresCallbackThreadContext() throws Exception {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync());
        assertThat(events).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        response.setStatus(202);
        MDC.put("correlationId", "outer-callback");
        request.getAsyncContext().complete();
        RequestLoggingFilter.completeRequest(request);
        assertThat(events).hasSize(1);
        assertThat(json(events.get(0)).path("http.response.status_code").asInt()).isEqualTo(202);
        assertThat(json(events.get(0)).path("correlationId").asText()).isEqualTo(response.getHeader("X-Correlation-ID"));
        assertThat(MDC.get("correlationId")).isEqualTo("outer-callback");
    }

    @Test
    void asyncErrorAndTimeoutKeepActualFinalStatus() throws Exception {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync());
        var async = (MockAsyncContext) request.getAsyncContext();
        var error = new AsyncEvent(async, request, response, new IllegalStateException("secret"));
        for (var listener : async.getListeners()) {
            listener.onError(error);
            listener.onTimeout(error);
        }
        response.setStatus(503);
        async.complete();
        for (var listener : async.getListeners()) listener.onComplete(error);
        assertThat(events).hasSize(1);
        var record = json(events.get(0));
        assertThat(record.path("http.response.status_code").asInt()).isEqualTo(503);
        assertThat(record.path("asyncTimedOut").asBoolean()).isTrue();
        assertThat(record.toString()).doesNotContain("\"secret\"");
    }

    @Test
    void headersBodiesPathsAndUnapprovedDiagnosticValuesNeverReachJson() throws Exception {
        request.addHeader("Authorization", "Bearer token-secret");
        request.addHeader("Cookie", "session=cookie-secret");
        request.addHeader("User-Agent", "username=user-agent-secret");
        request.setQueryString("password=query-secret");
        request.setContent("body-secret".getBytes(StandardCharsets.UTF_8));
        MDC.put("password", "mdc-secret");
        request.addHeader("X-Caller-Service", "bad\r\nheader");
        request.addHeader("traceparent", "invalid-parent-secret");
        filter.doFilter(request, response, (a, b) -> {
        });
        String record = json(events.get(0)).toString();
        assertThat(record).contains("UNMATCHED").doesNotContain("raw-secret", "token-secret", "cookie-secret",
                "user-agent-secret", "query-secret", "body-secret", "mdc-secret", "invalid-parent-secret");
    }

    @Test
    void callerHeadersAreValidatedAndW3cParentRemainsSeparateFromActualTrace() throws Exception {
        request.addHeader("X-Caller-Service", "learning-api");
        request.addHeader("X-Caller-Instance", "pod-1");
        request.addHeader("X-Caller-Version", "1.2.3");
        request.addHeader("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        filter.doFilter(request, response, (a, b) -> {
        });
        var record = json(events.get(0));
        assertThat(record.path("callerService").asText()).isEqualTo("learning-api");
        assertThat(record.path("callerTraceId").asText()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(record.has("traceId")).isFalse();
        assertThat(record.has("spanId")).isFalse();
    }

    @Test
    void successfulHealthRequestsAreDebugAndFailuresAreStillWarn() throws Exception {
        var health = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        filter.doFilter(health, new MockHttpServletResponse(), (a, b) -> {
        });
        assertThat(events).isEmpty();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness"), new MockHttpServletResponse(),
                (a, b) -> ((jakarta.servlet.http.HttpServletResponse) b).setStatus(503));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    void debugHasOneStartAndOneCompletionWithoutDuplicateFields() throws Exception {
        logger.setLevel(Level.DEBUG);
        filter.doFilter(request, response, (a, b) -> {
        });
        RequestLoggingFilter.completeRequest(request);
        assertThat(events).hasSize(2);
        assertThat(json(events.get(0)).path("event").asText()).isEqualTo("http.request.started");
        assertThat(json(events.get(1)).path("event").asText()).isEqualTo("http.request.completed");
    }
}
