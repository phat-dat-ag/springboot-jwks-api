package com.japaneselearning.jwks.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Final privacy boundary before export, independent of HTTP observation conventions.
 * No URL, query, header, body, exception message, event or baggage attributes leave here.
 */
public final class SafeSpanExporter implements SpanExporter {
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 256;
    private static final Set<String> SAFE_ATTRIBUTES = Set.of(
            "method",
            "http.method",
            "http.request.method",
            "status",
            "http.status_code",
            "http.response.status_code",
            "uri",
            "http.route",
            "outcome",
            "exception",
            "error.type",
            "client.name"
    );

    private final SpanExporter delegate;

    public SafeSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return delegate.export(spans.stream().<SpanData>map(SafeSpan::new).toList());
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private static final class SafeSpan extends DelegatingSpanData {
        private final Attributes attributes;

        private SafeSpan(SpanData original) {
            super(original);

            var safe = original.getAttributes().toBuilder();

            original.getAttributes().forEach((key, value) -> {
                String text = String.valueOf(value);

                if (!SAFE_ATTRIBUTES.contains(key.getKey())
                        || text.length() > MAX_ATTRIBUTE_VALUE_LENGTH
                        || !text.matches("[A-Za-z0-9_./{} -]+")
                ) {
                    safe.remove(key);
                }
            });

            attributes = safe.build();
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        public int getTotalAttributeCount() {
            return attributes.size();
        }

        @Override
        public List<EventData> getEvents() {
            return List.of();
        }

        @Override
        public int getTotalRecordedEvents() {
            return 0;
        }

        @Override
        public List<LinkData> getLinks() {
            return List.of();
        }

        @Override
        public int getTotalRecordedLinks() {
            return 0;
        }

        @Override
        public StatusData getStatus() {
            return StatusData.create(super.getStatus().getStatusCode(), "");
        }
    }
}
