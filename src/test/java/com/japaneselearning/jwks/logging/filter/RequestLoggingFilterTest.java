package com.japaneselearning.jwks.logging.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.japaneselearning.jwks.logging.StructuredJsonEncoder;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ServletException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.*;

class RequestLoggingFilterTest {
    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final List<ILoggingEvent> events = new ArrayList<>();
    private ListAppender<ILoggingEvent> appender;
    private boolean previousAdditive;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        MDC.clear();
        previousAdditive = logger.isAdditive();
        logger.setAdditive(false);
        request = new MockHttpServletRequest("GET", "/keys/secret-token");
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
        logger.setAdditive(previousAdditive);
        appender.stop();
        MDC.clear();
    }

    @Test
    void generatesIdPropagatesItAndLogsNormalizedRouteStatusAndDuration() throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("correlationId")).isEqualTo(response.getHeader("X-Correlation-ID"));
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/keys/{kid}");
            response.setStatus(201);
        });
        UUID.fromString(response.getHeader("X-Correlation-ID"));
        assertThat(events).hasSize(1);
        var fields = events.get(0).getMDCPropertyMap();
        assertThat(fields).containsEntry("httpMethod", "GET").containsEntry("uri", "/keys/{kid}")
                .containsEntry("status", "201");
        assertThat(Long.parseLong(fields.get("elapsed_ms"))).isGreaterThanOrEqualTo(0);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void preservesValidIncomingIdAndExistingTraceScope() throws Exception {
        request.addHeader("X-Correlation-ID", "request-123_abc.4");
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");
        MDC.put("spanId", "0123456789abcdef");
        filter.doFilter(request, response, (req, res) -> {
        });
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("request-123_abc.4");
        assertThat(events.get(0).getMDCPropertyMap()).containsEntry("traceId", MDC.get("traceId"))
                .containsEntry("spanId", MDC.get("spanId"));
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void rejectsUnsafeOversizedAndAmbiguousIds() throws Exception {
        for (String value : List.of("bad\r\nheader", "x".repeat(65), "space value", "")) {
            var req = new MockHttpServletRequest("GET", "/");
            var res = new MockHttpServletResponse();
            req.addHeader("X-Correlation-ID", value);
            filter.doFilter(req, res, (a, b) -> {
            });
            UUID.fromString(res.getHeader("X-Correlation-ID"));
        }
        request.addHeader("X-Correlation-ID", "first");
        request.addHeader("X-Correlation-ID", "second");
        filter.doFilter(request, response, (a, b) -> {
        });
        UUID.fromString(response.getHeader("X-Correlation-ID"));
    }

    @Test
    void logsExpectedRejectionAtWarnAndDoesNotLeakIntoNextRequest() throws Exception {
        MDC.put("correlationId", "stale");
        MDC.put("uri", "/old-secret");
        filter.doFilter(request, response, (req, res) -> response.setStatus(401));
        assertThat(events.get(0).getLevel().toString()).isEqualTo("WARN");
        assertThat(events.get(0).getMDCPropertyMap()).containsEntry("status", "401");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        var nextResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/"), nextResponse, (req, res) -> {
        });
        assertThat(nextResponse.getHeader("X-Correlation-ID")).isNotEqualTo(response.getHeader("X-Correlation-ID"));
    }

    @Test
    void propagatesFailureLogs500OnceAndCleansMdc() throws Exception {
        var failure = new ServletException("password=do-not-log", new IllegalStateException("private-key-secret"));
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw failure;
        }))
                .isSameAs(failure);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel().toString()).isEqualTo("ERROR");
        assertThat(events.get(0).getMDCPropertyMap()).containsEntry("status", "500")
                .containsEntry("exception_type", ServletException.class.getName());
        JsonNode json = encode(events.get(0));
        assertThat(json.get("stack_trace").asText()).contains("jakarta.servlet.ServletException",
                "java.lang.IllegalStateException", "Caused by:", "RequestLoggingFilterTest");
        assertThat(json.toString()).doesNotContain("do-not-log", "private-key-secret");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void logsAsyncCompletionOnlyOnceAndPreservesCorrelation() throws Exception {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync());
        assertThat(events).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        response.setStatus(202);
        request.getAsyncContext().complete();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getMDCPropertyMap()).containsEntry("status", "202")
                .containsEntry("correlationId", response.getHeader("X-Correlation-ID"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void recordsAsyncErrorWithoutDuplicateCompletion() throws Exception {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync());
        var async = (MockAsyncContext) request.getAsyncContext();
        var error = new AsyncEvent(async, request, response, new IllegalStateException("secret"));
        for (var listener : async.getListeners()) listener.onError(error);
        async.complete();
        for (var listener : async.getListeners()) listener.onComplete(error);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getMDCPropertyMap()).containsEntry("status", "500");
    }

    @Test
    void recordsAsyncTimeoutAsFailure() throws Exception {
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> req.startAsync());
        var async = (MockAsyncContext) request.getAsyncContext();
        for (var listener : async.getListeners()) listener.onTimeout(new AsyncEvent(async));
        async.complete();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel().toString()).isEqualTo("ERROR");
        assertThat(events.get(0).getMDCPropertyMap()).containsEntry("status", "500");
    }

    @Test
    void doesNotLogHeadersBodiesQueryStringsRawPathsOrUnapprovedMdc() throws Exception {
        request.addHeader("Authorization", "Bearer token-secret");
        request.addHeader("Cookie", "session=cookie-secret");
        request.setQueryString("password=query-secret");
        request.setContent("private-key-body".getBytes(StandardCharsets.UTF_8));
        MDC.put("password", "mdc-secret");
        filter.doFilter(request, response, (req, res) -> {
        });
        String json = encode(events.get(0)).toString();
        assertThat(json).contains("UNMATCHED").doesNotContain("token-secret", "cookie-secret",
                "query-secret", "private-key-body", "secret-token", "mdc-secret");
    }

    @Test
    void capturesCallerContextInApplicationAndAsyncCompletionLogs() throws Exception {
        request.addHeader("X-Caller-Service", "user-service");
        request.addHeader("X-Caller-Instance", "user-service-pod-7");
        request.addHeader("X-Caller-Version", "1.2.3");
        request.addHeader("User-Agent", "Java-http-client/17");
        request.addHeader("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        request.setRemoteAddr("10.0.0.2");
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("callerService")).isEqualTo("user-service");
            RequestLoggingFilter.recordErrorCode(request, "INTERNAL_SERVER_ERROR");
            req.startAsync();
        });
        response.setStatus(500);
        request.getAsyncContext().complete();
        assertThat(events).hasSize(1);
        var json = encode(events.get(0));
        assertThat(json.path("callerService").asText()).isEqualTo("user-service");
        assertThat(json.path("callerInstance").asText()).isEqualTo("user-service-pod-7");
        assertThat(json.path("callerVersion").asText()).isEqualTo("1.2.3");
        assertThat(json.path("callerIdentitySource").asText()).isEqualTo("self-reported");
        assertThat(json.path("peerAddress").asText()).isEqualTo("10.0.0.2");
        assertThat(json.path("callerTraceId").asText()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(json.path("callerSpanId").asText()).isEqualTo("0123456789abcdef");
        assertThat(json.path("errorCode").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(json.toString()).doesNotContain("203.0.113.10");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void rejectsAmbiguousCallerIdentityAndInvalidTraceAndBoundsUserAgent() throws Exception {
        request.addHeader("X-Caller-Service", "first");
        request.addHeader("X-Caller-Service", "second");
        request.addHeader("X-Caller-Instance", "bad\r\nvalue");
        request.addHeader("X-Caller-Version", "x".repeat(65));
        request.addHeader("traceparent", "00-" + "0".repeat(32) + "-0123456789abcdef-01");
        request.addHeader("User-Agent", "bad\r\n\u001b" + "x".repeat(300));
        filter.doFilter(request, response, (req, res) -> {
        });
        var fields = events.get(0).getMDCPropertyMap();
        assertThat(fields).containsEntry("callerService", "unknown")
                .containsEntry("callerInstance", "unknown").containsEntry("callerVersion", "unknown")
                .doesNotContainKeys("callerTraceId", "callerSpanId");
        assertThat(fields.get("userAgent")).hasSize(256).doesNotContain("\r", "\n", "\u001b");
        filter.doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), (a, b) -> {
        });
        assertThat(events.get(1).getMDCPropertyMap()).containsEntry("callerService", "unknown")
                .containsEntry("userAgent", "unknown");
    }

    private JsonNode encode(ILoggingEvent event) throws Exception {
        var encoder = new StructuredJsonEncoder();
        encoder.setContext(logger.getLoggerContext());
        encoder.start();
        try {
            return new ObjectMapper().readTree(encoder.encode(event));
        } finally {
            encoder.stop();
        }
    }
}
