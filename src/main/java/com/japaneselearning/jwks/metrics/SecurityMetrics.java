package com.japaneselearning.jwks.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * One terminal security classification per completed request; never an account/token label.
 */
public final class SecurityMetrics {
    private final Map<String, Counter> counters = new HashMap<>();

    public SecurityMetrics(MeterRegistry registry) {
        for (String operation : new String[]{"login", "refresh", "authentication", "internal_authentication", "authorization"}) {
            for (String result : new String[]{"success", "failure"}) {
                if (!result.equals("success") || operation.equals("login") || operation.equals("refresh")) {
                    counters.put(operation + ":" + result, Counter.builder("app.security.events")
                            .description("Terminal HTTP security results; login/refresh are classified before general rejections")
                            .tags("operation", operation, "result", result).register(registry));
                }
            }
        }
    }

    public void completed(String method, String route, boolean internal, int status) {
        String operation;

        if (method.equals("POST") && "/auth/login".equals(route))
            operation = "login";
        else if (method.equals("POST") && "/auth/refresh".equals(route))
            operation = "refresh";
        else if (status == 401)
            operation = internal ? "internal_authentication" : "authentication";
        else if (status == 403)
            operation = "authorization";
        else return;

        String result = status >= 200 && status < 300 ? "success" : "failure";

        counters.get(operation + ":" + result).increment();
    }
}
