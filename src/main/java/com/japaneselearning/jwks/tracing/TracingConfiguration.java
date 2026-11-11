package com.japaneselearning.jwks.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {
    @Bean
    @Order(MicrometerTracingAutoConfiguration.DEFAULT_TRACING_OBSERVATION_HANDLER_ORDER)
    DefaultTracingObservationHandler applicationTracing(Tracer tracer) {
        return new DefaultTracingObservationHandler(tracer) {
            @Override
            public void onStart(Observation.Context context) {
                if (securityContext(context)) {
                    getTracingContext(context).setSpan(tracer.currentSpan());
                } else {
                    super.onStart(context);
                }
            }

            @Override
            public void onStop(Observation.Context context) {
                if (!securityContext(context)) {
                    super.onStop(context);
                }
            }

            @Override
            public void onError(Observation.Context context) {
                if (!securityContext(context)) {
                    super.onError(context);
                }
            }

            @Override
            public void onEvent(Observation.Event event, Observation.Context context) {
                if (!securityContext(context)) {
                    super.onEvent(event, context);
                }
            }

            private boolean securityContext(Observation.Context context) {
                String name = context.getName();

                return name != null && name.startsWith("spring.security.");
            }
        };
    }

    @Bean
    static BeanPostProcessor safeTracingExport() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SpanExporter exporter
                        && !(bean instanceof SafeSpanExporter)
                ) {
                    return new SafeSpanExporter(exporter);
                }

                return bean;
            }
        };
    }
}
