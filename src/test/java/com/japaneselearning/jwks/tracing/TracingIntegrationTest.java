package com.japaneselearning.jwks.tracing;

import com.japaneselearning.jwks.logging.RequestLoggingConfiguration;
import com.japaneselearning.jwks.logging.filter.RequestLoggingFilter;

import ch.qos.logback.classic.LoggerContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class TracingIntegrationTest {
    private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";
    private static final String PARENT_ID = "1234567890abcdef";
    private static final List<SpanData> SPANS = new CopyOnWriteArrayList<>();
    private static final String EXCLUSIONS = String.join(
            ",",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    );

    @TempDir
    Path logs;

    @Test
    void realHttpContinuesW3cWithOneClientAndTwoServerSpans() throws Exception {
        verifyTracing("1.0", true);
    }

    @Test
    void zeroRootSamplingStillHonorsSampledRemoteParents() throws Exception {
        verifyTracing("0.0", false);
    }

    private void verifyTracing(String probability, boolean sampleRoots) throws Exception {
        String previous = System.getProperty("APP_LOG_DIR");

        System.setProperty("APP_LOG_DIR", logs.toString());
        SPANS.clear();

        try (var context = new SpringApplicationBuilder(Fixture.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.config.location=file:src/main/resources/application.properties",
                        "--server.port=0",
                        "--spring.autoconfigure.exclude=" + EXCLUSIONS,
                        "--management.tracing.sampling.probability=" + probability,
                        "--management.tracing.export.otlp.enabled=false"
                )
        ) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/entry"))
                    .header("traceparent", "00-" + TRACE_ID + "-" + PARENT_ID + "-01")
                    .header("tracestate", "vendor=value")
                    .header("X-Correlation-ID", "trace-correlation")
                    .header("Authorization", "Bearer SECRET_TOKEN")
                    .header("Cookie", "secret=SECRET_COOKIE")
                    .header("baggage", "email=SECRET_BAGGAGE")
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Correlation-ID"))
                    .contains("trace-correlation");
            assertThat(response.body()).startsWith(TRACE_ID + ":").endsWith(":trace-correlation");

            awaitSpans(3);

            assertThat(SPANS).hasSize(3);
            assertThat(context.getBean(MeterRegistry.class)
                    .find("spring.security.http.secured.requests").timers()).isNotEmpty();
            assertThat(SPANS).allSatisfy(span -> {
                assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
                assertThat(span.getSpanId()).matches("[0-9a-f]{16}");
                assertThat(span.getEndEpochNanos()).isGreaterThan(span.getStartEpochNanos());
                assertThat(span.getEvents()).isEmpty();
                assertThat(span.getAttributes().toString()).doesNotContain(
                        "SECRET", "Authorization", "Cookie", "password", "url", "secret-person"
                );
            });

            var entry = SPANS.stream().filter(span -> span.getParentSpanId().equals(PARENT_ID))
                    .findFirst().orElseThrow();
            var outgoing = SPANS.stream().filter(span -> span.getKind().name().equals("CLIENT"))
                    .findFirst().orElseThrow();
            var downstream = SPANS.stream()
                    .filter(span -> span.getParentSpanId().equals(outgoing.getSpanId()))
                    .findFirst().orElseThrow();

            assertThat(outgoing.getParentSpanId()).isEqualTo(entry.getSpanId());
            assertThat(downstream.getKind().name()).isEqualTo("SERVER");
            assertThat(downstream.getSpanId()).isNotEqualTo(outgoing.getSpanId());
            assertThat(outgoing.getSpanContext().getTraceState().get("vendor")).isEqualTo("value");

            SPANS.clear();

            var malformed = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/probe/secret-person")
                    )
                    .header("traceparent", "00-" + "0".repeat(32) + "-" + PARENT_ID + "-01")
                    .build();
            var fresh = client.send(malformed, HttpResponse.BodyHandlers.ofString());
            String[] fields = fresh.body().split(":");

            assertThat(fields[0]).matches("[0-9a-f]{32}")
                    .isNotEqualTo(TRACE_ID)
                    .isNotEqualTo("0".repeat(32));
            assertThat(fields[1]).matches("[0-9a-f]{16}");
            assertThat(fields[2]).isNotEqualTo("trace-correlation");

            if (sampleRoots) {
                awaitSpans(1);

                assertThat(SPANS).hasSize(1);
                assertThat(SPANS.get(0).getParentSpanId()).isEqualTo("0".repeat(16));
            } else {
                Thread.sleep(300);

                assertThat(SPANS).isEmpty();
            }

            SPANS.clear();

            var async = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/async-probe")
                    )
                    .header("traceparent", "00-" + TRACE_ID + "-" + PARENT_ID + "-01")
                    .header("X-Correlation-ID", "async-correlation")
                    .build();
            var asyncResponse = client.send(async, HttpResponse.BodyHandlers.ofString());

            assertThat(asyncResponse.statusCode()).isEqualTo(200);
            assertThat(asyncResponse.body()).startsWith(TRACE_ID + ":")
                    .endsWith(":async-correlation");

            awaitSpans(1);

            assertThat(SPANS).hasSize(1);
            assertThat(SPANS.get(0).getParentSpanId()).isEqualTo(PARENT_ID);
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("spanId")).isNull();
            assertThat(MDC.get("correlationId")).isNull();
        } finally {
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();

            if (previous == null) {
                System.clearProperty("APP_LOG_DIR");
            } else {
                System.setProperty("APP_LOG_DIR", previous);
            }
        }
    }

    private static void awaitSpans(int count) throws InterruptedException {
        for (int attempt = 0; attempt < 500 && SPANS.size() < count; attempt++) {
            Thread.sleep(20);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
            TracingConfiguration.class,
            RequestLoggingConfiguration.class,
            RequestLoggingFilter.class
    })
    static class Fixture {
        @Bean
        SpanExporter capturedSpans() {
            return new SpanExporter() {
                @Override
                public CompletableResultCode export(Collection<SpanData> spans) {
                    SPANS.addAll(spans);

                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }

        @Bean
        ProbeController probeController(RestClient.Builder builder, Tracer tracer) {
            return new ProbeController(builder, tracer);
        }
    }

    @RestController
    static class ProbeController {
        private final RestClient.Builder builder;
        private final Tracer tracer;

        ProbeController(RestClient.Builder builder, Tracer tracer) {
            this.builder = builder;
            this.tracer = tracer;
        }

        @GetMapping("/entry")
        String entry(HttpServletRequest request) {
            return builder.clone().baseUrl("http://127.0.0.1:" + request.getLocalPort())
                    .defaultHeader("X-Correlation-ID", MDC.get("correlationId"))
                    .build().get().uri("/probe/{id}?password=SECRET_QUERY", "secret-person")
                    .retrieve().body(String.class);
        }

        @GetMapping("/probe/{id}")
        String probe() {
            var attributes =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            var request = attributes.getRequest();

            assertThat(request.getHeader("baggage")).isNull();
            assertThat(request.getHeader("Authorization")).isNull();
            assertThat(request.getHeader("Cookie")).isNull();

            var current = tracer.currentSpan().context();

            return current.traceId() + ":" + current.spanId() + ":" + MDC.get("correlationId");
        }

        @GetMapping("/async-probe")
        Callable<String> asyncProbe() {
            return this::probe;
        }
    }
}
