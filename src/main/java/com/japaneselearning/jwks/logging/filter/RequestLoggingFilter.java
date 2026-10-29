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
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "correlationId", "httpMethod", "uri", "status", "elapsed_ms", "exception_type"
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

    private static final class RequestState implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String correlationId;
        private final long started = System.nanoTime();
        private final AtomicBoolean logged = new AtomicBoolean();
        private final AtomicBoolean listenerRegistered = new AtomicBoolean();
        private volatile Throwable failure;
        private volatile boolean timedOut;
        private volatile String traceId;
        private volatile String spanId;

        private RequestState(HttpServletRequest request, HttpServletResponse response, String correlationId) {
            this.request = request;
            this.response = response;
            this.correlationId = correlationId;
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