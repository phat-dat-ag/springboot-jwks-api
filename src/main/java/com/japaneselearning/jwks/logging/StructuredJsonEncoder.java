package com.japaneselearning.jwks.logging;

import java.util.List;

import net.logstash.logback.encoder.LogstashEncoder;

/**
 * One shared field allowlist and exception policy for console and file output.
 */
public final class StructuredJsonEncoder extends LogstashEncoder {
    public StructuredJsonEncoder() {
        setIncludeContext(false);

        setIncludeMdcKeyNames(List.of(
                "correlationId",
                "traceId",
                "spanId",
                "httpMethod",
                "uri",
                "status",
                "elapsed_ms",
                "exception_type"
        ));

        setThrowableConverter(new SafeThrowableConverter());
    }
}