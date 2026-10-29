package com.japaneselearning.jwks.logging;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Retains stack frames and cause types without rendering potentially secret exception messages.
 */
public final class SafeThrowableConverter extends ThrowableHandlingConverter {
    @Override
    public String convert(ILoggingEvent event) {
        StringBuilder result = new StringBuilder();

        append(
                result,
                event.getThrowableProxy(),
                "",
                Collections.newSetFromMap(new IdentityHashMap<>()),
                0
        );

        return result.toString();
    }

    private void append(
            StringBuilder result,
            IThrowableProxy error,
            String prefix,
            Set<IThrowableProxy> seen,
            int depth
    ) {
        if (error == null || !seen.add(error) || depth >= 16) {
            return;
        }

        result.append(prefix).append(error.getClassName()).append(" [message omitted]\n");

        for (var frame : error.getStackTraceElementProxyArray()) {
            result.append("\tat ").append(frame.getStackTraceElement()).append('\n');
        }

        if (error.getSuppressed() != null) {
            for (var suppressed : error.getSuppressed()) {
                append(result, suppressed, "Suppressed: ", seen, depth + 1);
            }
        }

        append(result, error.getCause(), "Caused by: ", seen, depth + 1);
    }
}