package com.japaneselearning.jwks.tracing;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafeSpanExporterTest {
    @Test
    void exportsSafeHttpStatusWithoutCredentialsOrExceptionMessages() {
        var captured = new ArrayList<SpanData>();
        var exporter = new SafeSpanExporter(new CapturingExporter(captured));

        try (var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        ) {
            var span = provider.get("privacy-test").spanBuilder("http get /users/{id}").startSpan();

            span.setAttribute("method", "GET");
            span.setAttribute("uri", "/users/{id}");
            span.setAttribute("http.response.status_code", 500L);
            span.setAttribute("http.url", "https://user:SECRET@host/users/private?password=SECRET");
            span.setAttribute("Authorization", "Bearer SECRET");
            span.setAttribute("email", "private@example.invalid");
            span.setAttribute("exception.message", "SECRET");
            span.recordException(new IllegalArgumentException("SECRET"));
            span.setStatus(StatusCode.ERROR, "SECRET");
            span.end();

            assertThat(captured).hasSize(1);

            var exported = captured.get(0);

            assertThat(exported.getAttributes().size()).isEqualTo(3);
            assertThat(exported.getAttributes().toString()).contains("/users/{id}", "500");
            assertThat(exported.getAttributes().toString())
                    .doesNotContain("SECRET", "private", "email");
            assertThat(exported.getEvents()).isEmpty();
            assertThat(exported.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(exported.getStatus().getDescription()).isEmpty();
            assertThat(exported.getTotalRecordedEvents()).isZero();
            assertThat(exported.getTotalAttributeCount()).isEqualTo(3);
        }
    }

    private record CapturingExporter(List<SpanData> spans) implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);

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
    }
}
