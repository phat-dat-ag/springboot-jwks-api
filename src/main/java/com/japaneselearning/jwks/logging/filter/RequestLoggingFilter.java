package com.japaneselearning.jwks.logging.filter;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String STATE_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".state";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern TRACE_PARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "correlationId", "httpMethod", "uri", "status", "elapsed_ms", "exception_type",
            "event", "callerService", "callerInstance", "callerVersion", "callerIdentitySource",
            "peerAddress", "userAgent", "protocol", "callerTraceId", "callerSpanId", "errorCode",
            "key_count", "asyncTimedOut"
    );
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        RequestState state = (RequestState) request.getAttribute(STATE_ATTRIBUTE);
        if (state == null) {
            String incoming = request.getHeader(CORRELATION_HEADER);
            // Reject duplicate headers as ambiguous as well as oversized or unsafe values.
            var headers = request.getHeaders(CORRELATION_HEADER);

            if (headers.hasMoreElements()) {
                headers.nextElement();
            }

            String id = incoming != null && VALID_ID.matcher(incoming).matches() && !headers.hasMoreElements()
                    ? incoming : UUID.randomUUID().toString();

            state = new RequestState(request, response, id);

            request.setAttribute(STATE_ATTRIBUTE, state);
            response.setHeader(CORRELATION_HEADER, id);
        }

        RequestState current = state;

        try (MdcScope ignored = new MdcScope(current, isAsyncDispatch(request))) {
            current.captureTrace();

            if (current.startedLogged.compareAndSet(false, true)) {
                log.atDebug()
                        .addKeyValue("event", "http.request.started")
                        .log("API request started");
            }

            HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
                @Override
                public AsyncContext startAsync() {
                    return startAsync(this, response);
                }

                @Override
                public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
                    AsyncContext context = super.startAsync(req, res);

                    if (current.listenerRegistered.compareAndSet(false, true)) {
                        context.addListener(current);
                    }

                    return context;
                }
            };

            try {
                chain.doFilter(wrapped, response);
            } catch (IOException | ServletException | RuntimeException | Error ex) {
                current.failure = ex;
                throw ex; // Preserve the existing container/API error handling.
            } finally {
                current.captureTrace();

                if (!request.isAsyncStarted()) {
                    current.complete();
                }
            }
        }
    }

    /**
     * Hand diagnostics to the existing completion event instead of logging an exception twice.
     */
    public static boolean recordHandledFailure(HttpServletRequest request, Throwable failure) {
        if (request.getAttribute(STATE_ATTRIBUTE) instanceof RequestState state) {
            state.failure = failure;

            return true;
        }

        return false;
    }

    public static void recordErrorCode(HttpServletRequest request, String code) {
        if (request.getAttribute(STATE_ATTRIBUTE) instanceof RequestState state) {
            state.errorCode = code;
        }
    }

    // Only explicitly approved headers are inspected. Duplicate values are ambiguous.
    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);

        if (values == null || !values.hasMoreElements())
            return null;

        String value = values.nextElement();

        return values.hasMoreElements() ? null : value;
    }

    private static String safeText(String value, int limit) {
        if (value == null || value.isBlank()) return "unknown";
        // Bound log volume and remove control characters, including terminal escape sequences.
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < value.length() && result.length() < limit; i++) {
            char c = value.charAt(i);
            result.append(c >= 32 && c <= 126 ? c : '_');
        }

        return result.toString();
    }

    private static String identity(HttpServletRequest request, String header) {
        String value = singleHeader(request, header);

        return value != null && VALID_ID.matcher(value).matches() ? value : "unknown";
    }

    private static final class RequestState implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String correlationId;
        private final long started = System.nanoTime();
        private final AtomicBoolean logged = new AtomicBoolean();
        private final AtomicBoolean startedLogged = new AtomicBoolean();
        private final AtomicBoolean listenerRegistered = new AtomicBoolean();
        private volatile Throwable failure;
        private volatile boolean timedOut;
        private volatile String traceId;
        private volatile String spanId;
        private volatile String errorCode;
        private final Map<String, String> metadata;

        private RequestState(HttpServletRequest request, HttpServletResponse response, String correlationId) {
            this.request = request;
            this.response = response;
            this.correlationId = correlationId;

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("callerService", identity(request, "X-Caller-Service"));
            fields.put("callerInstance", identity(request, "X-Caller-Instance"));
            fields.put("callerVersion", identity(request, "X-Caller-Version"));
            fields.put("callerIdentitySource", "self-reported");
            fields.put("peerAddress", safeText(request.getRemoteAddr(), 64));
            fields.put("userAgent", safeText(singleHeader(request, "User-Agent"), 256));
            fields.put("protocol", safeText(request.getProtocol(), 32));
            String parent = singleHeader(request, "traceparent");

            if (parent != null) {
                var match = TRACE_PARENT.matcher(parent);
                if (match.matches() && !match.group(1).equals("0".repeat(32))
                        && !match.group(2).equals("0".repeat(16))) {
                    fields.put("callerTraceId", match.group(1));
                    fields.put("callerSpanId", match.group(2));
                }
            }

            metadata = Map.copyOf(fields);
        }

        private void captureTrace() {
            if (MDC.get("traceId") != null) {
                traceId = MDC.get("traceId");
            }

            if (MDC.get("spanId") != null) {
                spanId = MDC.get("spanId");
            }
        }

        private void complete() {
            if (!logged.compareAndSet(false, true)) {
                return;
            }

            try (MdcScope ignored = new MdcScope(this, true)) {
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                // Never fall back to a raw URI: it can contain tokens, identifiers or secrets.

                MDC.put("uri", route instanceof String pattern ? pattern : "UNMATCHED");
                MDC.put("httpMethod", METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER");

                int status = response.getStatus();

                if ((failure != null || timedOut) && !response.isCommitted() && status < 500) {
                    status = 500;
                }

                MDC.put("status", Integer.toString(status));
                MDC.put("elapsed_ms", Long.toString((System.nanoTime() - started) / 1_000_000));
                MDC.put("event", "http.request.completed");
                MDC.put("asyncTimedOut", Boolean.toString(timedOut));

                if (errorCode != null) MDC.put("errorCode", errorCode);

                if (failure != null) {
                    MDC.put("exception_type", failure.getClass().getName());
                    log.error("API request failed", failure);
                } else if (timedOut || status >= 500) {
                    log.error("API request failed");
                } else if (status >= 400) {
                    log.warn("API request rejected");
                } else {
                    log.info("API request completed");
                }
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            timedOut = true;
        }

        @Override
        public void onError(AsyncEvent event) {
            failure = event.getThrowable();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }

    /**
     * Own only request metadata; preserve tracing/other scopes installed by upstream code.
     */
    private static final class MdcScope implements AutoCloseable {
        private final Map<String, String> previous = MDC.getCopyOfContextMap();

        private MdcScope(RequestState state, boolean restoreTrace) {
            REQUEST_FIELDS.forEach(MDC::remove);

            MDC.put("correlationId", state.correlationId);

            state.metadata.forEach(MDC::put);

            if (restoreTrace) {
                MDC.remove("traceId");
                MDC.remove("spanId");

                if (state.traceId != null)
                    MDC.put("traceId", state.traceId);

                if (state.spanId != null)
                    MDC.put("spanId", state.spanId);
            }
        }

        @Override
        public void close() {
            MDC.clear();

            if (previous != null) {
                previous.forEach((key, value) -> {
                    if (!REQUEST_FIELDS.contains(key))
                        MDC.put(key, value);
                });
            }
        }
    }
}
