package com.japaneselearning.jwks.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;

import java.io.IOException;

/**
 * Framework exception messages may embed request paths, credentials or rejected values.
 */
public final class SafeMessageJsonProvider extends MessageJsonProvider {
    @Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
        String name = event.getLoggerName();

        boolean unsafeFrameworkMessage = name != null && (
                name.startsWith("org.springframework.boot.diagnostics.")
                        || name.startsWith("org.apache.coyote.")
                        || name.endsWith("SqlExceptionHelper")
                        || name.endsWith("DefaultHandlerExceptionResolver")
        );

        if (event.getThrowableProxy() != null || unsafeFrameworkMessage) {
            generator.writeStringField(
                    getFieldName(),
                    "Operation failed; see error type and stack frames"
            );
        } else {
            super.writeTo(generator, event);
        }
    }
}
