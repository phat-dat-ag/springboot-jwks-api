package com.japaneselearning.jwks.logging;

import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;

import java.util.List;

/**
 * Console/file share one field policy, timestamp format and safe exception rendering.
 */
public final class StructuredJsonEncoder extends LogstashEncoder {
    private String serviceName = "unknown";
    private String serviceVersion = "unknown";
    private String environment = "development";

    public void setServiceName(String value) {
        serviceName = metadata(value, 64);
        updateMetadata();
    }

    public void setServiceVersion(String value) {
        serviceVersion = metadata(value, 128);
        updateMetadata();
    }

    public void setEnvironment(String value) {
        environment = metadata(value, 64);
        updateMetadata();
    }

    private static String metadata(String value, int maximum) {
        return value != null
                && value.length() <= maximum
                && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                ? value : "unknown";
    }

    private void updateMetadata() {
        // Values are allowlisted before JSON construction, never raw environment interpolation.
        setCustomFields("{\"service\":\"" + serviceName
                + "\",\"serviceVersion\":\"" + serviceVersion
                + "\",\"environment\":\"" + environment + "\"}"
        );
    }

    public StructuredJsonEncoder() {
        setIncludeContext(false);
        setIncludeStructuredArguments(false);
        setIncludeNonStructuredArguments(false);
        setTimeZone("UTC");
        setTimestampPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        setIncludeMdcKeyNames(List.of(
                "correlationId",
                "traceId",
                "spanId",
                "callerService",
                "callerInstance",
                "callerVersion",
                "callerIdentitySource",
                "peerAddress",
                "protocol",
                "callerTraceId",
                "callerSpanId",
                "key_count"
        ));
        for (String name : List.of(
                "event", "http.request.method", "http.route", "http.response.status_code",
                "durationMs", "outcome", "error.category", "error.type", "errorCode", "asyncTimedOut",
                "key_count", "startup_ms", "stage", "key_index", "resultCount", "upstream.status", "upstream.errorType"
        )) {
            addIncludeKeyValueKeyName(name);
        }

        setThrowableConverter(new SafeThrowableConverter());

        var providers = getFormatter().getProviders();

        List.copyOf(providers.getProviders())
                .stream()
                .filter(MessageJsonProvider.class::isInstance)
                .forEach(providers::removeProvider);

        providers.addMessage(new SafeMessageJsonProvider());
        updateMetadata();
    }
}
