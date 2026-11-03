package com.japaneselearning.jwks.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Operational lifecycle markers; no environment values or configuration secrets.
 */
@Component
public final class ApplicationLifecycleLogger {
    private static final Logger log = LoggerFactory.getLogger(
            ApplicationLifecycleLogger.class
    );

    @EventListener
    public void ready(ApplicationReadyEvent event) {
        log.atInfo().addKeyValue("event", "application.ready")
                .addKeyValue(
                        "startup_ms",
                        event.getTimeTaken().toMillis()
                ).log("JWKS application ready");
    }

    @EventListener
    public void closing(ContextClosedEvent event) {
        log.atInfo().addKeyValue(
                "event",
                "application.stopping"
        ).log("JWKS application shutdown started");
    }
}
