package com.japaneselearning.jwks.logging.filter;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.Callable;

import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;

import com.japaneselearning.jwks.metrics.SecurityMetrics;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Owns diagnostic context and one final HTTP event, never request/response bodies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String CORRELATION_ID = "correlationId";
    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String CORRELATION_HEADER = HEADER_NAME;

    private static final String STATE = RequestLoggingFilter.class.getName() + ".state";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern TRACE_PARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    private static final Set<String> OWNED = Set.of(
            "correlationId",
            "callerService",
            "callerInstance",
            "callerVersion",
            "callerIdentitySource",
            "peerAddress",
            "protocol",
            "callerTraceId",
            "callerSpanId",
            "event",
            "errorCode",
            "key_count",
            "httpMethod",
            "uri",
            "status",
            "elapsed_ms",
            "userAgent"
    );
    private SecurityMetrics securityMetrics;

    @Autowired(required = false)
    public void setSecurityMetrics(SecurityMetrics metrics) {
        this.securityMetrics = metrics;
    }

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        RequestState state = (RequestState) request.getAttribute(STATE);

        if (state == null) {
            String incoming = singleHeader(request, HEADER_NAME);
            state = new RequestState(
                    request,
                    response,
                    incoming != null && ID.matcher(incoming).matches()
                            ? incoming
                            : UUID.randomUUID().toString(),
                    securityMetrics
            );
            request.setAttribute(STATE, state);
        }

        RequestState current = state;

        WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(STATE, new CallableProcessingInterceptor() {
            private final ThreadLocal<MdcScope> scope = new ThreadLocal<>();

            @Override
            public <T> void preProcess(NativeWebRequest req, Callable<T> task) {
                scope.set(new MdcScope(current));
            }

            @Override
            public <T> void postProcess(NativeWebRequest req, Callable<T> task, Object result) {
                MdcScope active = scope.get();
                if (active != null) active.close();
                scope.remove();
            }
        });

        if (!response.isCommitted())
            response.setHeader(HEADER_NAME, current.correlationId);

        try (MdcScope ignored = new MdcScope(current, true)) {
            current.captureTrace();

            if (current.startedLogged.compareAndSet(false, true)) {
                log.atDebug().addKeyValue("event", "http.request.started")
                        .addKeyValue("http.request.method", current.method()).log("API request started");
            }

            HttpServletResponse wrappedResponse = new HttpServletResponseWrapper(response) {
                @Override
                public void sendError(int status) throws IOException {
                    current.errorPending = true;
                    super.sendError(status);
                }

                @Override
                public void sendError(int status, String message) throws IOException {
                    current.errorPending = true;
                    super.sendError(status, message);
                }
            };

            HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(request) {
                @Override
                public AsyncContext startAsync() {
                    return startAsync(this, wrappedResponse);
                }

                @Override
                public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
                    AsyncContext context = super.startAsync(req, res);

                    current.async = true;

                    if (current.listenerRegistered.compareAndSet(false, true))
                        context.addListener(current);

                    return context;
                }
            };
            try {
                chain.doFilter(wrappedRequest, wrappedResponse);
            } catch (IOException | ServletException | RuntimeException | Error failure) {
                current.failure = failure;
                current.errorPending = true;

                throw failure;
            } finally {
                current.captureTrace();
                current.captureRoute(request);
                // AsyncListener owns async completion. ERROR dispatch or requestDestroyed owns sendError/escapes.
                if (!current.async && (!current.errorPending
                        || request.getDispatcherType() == DispatcherType.ERROR)
                ) {
                    current.complete();
                }
            }
        }
    }

    /**
     * Servlet request destruction is the fallback after the container has finalized an error.
     */
    public static void completeRequest(ServletRequest request) {
        if (request.getAttribute(STATE) instanceof RequestState state)
            state.complete();
    }

    public static boolean recordHandledFailure(
            HttpServletRequest request,
            Throwable failure
    ) {
        if (request.getAttribute(STATE) instanceof RequestState state) {
            state.failure = failure;

            return true;
        }

        return false;
    }

    public static void recordErrorCode(HttpServletRequest request, String code) {
        if (request.getAttribute(STATE) instanceof RequestState state
                && code != null
                && code.matches("[A-Z][A-Z0-9_]{0,63}")
        )
            state.errorCode = code;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);

        if (values == null || !values.hasMoreElements())
            return null;

        String value = values.nextElement();

        return values.hasMoreElements() ? null : value;
    }

    private static String identity(HttpServletRequest request, String header) {
        String value = singleHeader(request, header);

        return value != null && ID.matcher(value).matches() ? value : "unknown";
    }

    private static final class RequestState implements AsyncListener {
        private final SecurityMetrics metrics;
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String correlationId;
        private final long started = System.nanoTime();
        private final AtomicBoolean logged = new AtomicBoolean();
        private final AtomicBoolean startedLogged = new AtomicBoolean();
        private final AtomicBoolean listenerRegistered = new AtomicBoolean();
        private final Map<String, String> metadata;
        private volatile boolean async;
        private volatile boolean errorPending;
        private volatile boolean timedOut;
        private volatile Throwable failure;
        private volatile String route;
        private volatile String traceId;
        private volatile String spanId;
        private volatile String errorCode;

        private RequestState(
                HttpServletRequest request,
                HttpServletResponse response,
                String correlationId,
                SecurityMetrics metrics
        ) {
            this.metrics = metrics;
            this.request = request;
            this.response = response;
            this.correlationId = correlationId;

            Map<String, String> fields = new LinkedHashMap<>();

            fields.put("callerService", identity(request, "X-Caller-Service"));
            fields.put("callerInstance", identity(request, "X-Caller-Instance"));
            fields.put("callerVersion", identity(request, "X-Caller-Version"));
            fields.put("callerIdentitySource", "self-reported");
            // No forwarded headers, arbitrary User-Agent, cookies, query, path or body are captured.
            String peer = request.getRemoteAddr();
            if (peer != null && peer.matches("[0-9a-fA-F:.%]{1,64}"))
                fields.put("peerAddress", peer);

            String protocol = request.getProtocol();

            if (protocol != null && protocol.matches("HTTP/[0-9.]{1,8}"))
                fields.put("protocol", protocol);

            String parent = singleHeader(request, "traceparent");

            if (parent != null) {
                var match = TRACE_PARENT.matcher(parent);

                if (match.matches()
                        && !match.group(1).equals("0".repeat(32))
                        && !match.group(2).equals("0".repeat(16))
                ) {
                    fields.put("callerTraceId", match.group(1));
                    fields.put("callerSpanId", match.group(2));
                }
            }

            metadata = Map.copyOf(fields);
        }

        private String method() {
            return METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
        }

        private void captureTrace() {
            if (MDC.get("traceId") != null)
                traceId = MDC.get("traceId");

            if (MDC.get("spanId") != null)
                spanId = MDC.get("spanId");
        }

        private void captureRoute(HttpServletRequest current) {
            Object pattern = current.getAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
            );
            // Preserve the original template if an error dispatch subsequently maps to /error.
            if (route == null
                    && current.getDispatcherType() != DispatcherType.ERROR
                    && pattern instanceof String value
            ) {
                route = value;
            }
        }

        private void complete() {
            if (!logged.compareAndSet(false, true))
                return;

            try (MdcScope ignored = new MdcScope(this)) {
                captureRoute(request);

                int status = response.getStatus();
                // Fallback only when no actual error status has been set at final completion.
                if ((failure != null || timedOut)
                        && !response.isCommitted()
                        && status < 400
                )
                    status = 500;

                if (metrics != null)
                    metrics.completed(
                            method(),
                            route,
                            request.getRequestURI().startsWith("/internal/"),
                            status
                    );

                String outcome = status >= 500
                        ? "server_error"
                        : status >= 400
                        ? "client_error"
                        : status >= 100 && status < 400
                        ? "success"
                        : "unknown";

                String category = status == 401
                        ? "authentication"
                        : status == 403
                        ? "authorization"
                        : status >= 500
                        ? "server"
                        : status >= 400
                        ? "client" : "none";

                String path = request.getRequestURI();

                boolean health = path.equals("/actuator/health")
                        || path.startsWith("/actuator/health/")
                        || path.equals("/actuator/prometheus");

                Level level = status >= 500
                        ? Level.ERROR
                        : status >= 400
                        ? Level.WARN
                        : health
                        ? Level.DEBUG
                        : Level.INFO;

                var event = log.atLevel(level).addKeyValue("event", "http.request.completed")
                        .addKeyValue("http.request.method", method())
                        .addKeyValue("http.route", route == null ? "UNMATCHED" : route)
                        .addKeyValue("http.response.status_code", status)
                        .addKeyValue("durationMs", Math.max(0L, (System.nanoTime() - started) / 1_000_000))
                        .addKeyValue("outcome", outcome).addKeyValue("error.category", category)
                        .addKeyValue("asyncTimedOut", timedOut);

                if (errorCode != null)
                    event.addKeyValue("errorCode", errorCode);

                if (failure != null)
                    event.addKeyValue(
                            "error.type",
                            failure.getClass().getName()
                    ).setCause(failure);

                event.log(status >= 500
                        ? "API request failed"
                        : status >= 400
                        ? "API request rejected"
                        : "API request completed"
                );
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
     * Restore the outer (including framework trace) scope, discarding request-owned stale values.
     */
    private static final class MdcScope implements AutoCloseable {
        private final Map<String, String> previous = MDC.getCopyOfContextMap();
        private final boolean discardOwned;

        private MdcScope(RequestState state) {
            this(state, false);
        }

        private MdcScope(RequestState state, boolean discardOwned) {
            this.discardOwned = discardOwned;

            OWNED.forEach(MDC::remove);

            MDC.put(CORRELATION_ID, state.correlationId);

            state.metadata.forEach(MDC::put);

            if (state.traceId != null)
                MDC.put("traceId", state.traceId);

            if (state.spanId != null)
                MDC.put("spanId", state.spanId);
        }

        @Override
        public void close() {
            MDC.clear();
            if (previous != null) previous.forEach((key, value) -> {
                if (!discardOwned || !OWNED.contains(key)) MDC.put(key, value);
            });
        }
    }
}
