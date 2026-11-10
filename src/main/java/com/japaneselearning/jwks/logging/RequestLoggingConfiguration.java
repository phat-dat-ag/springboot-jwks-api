package com.japaneselearning.jwks.logging;

import com.japaneselearning.jwks.logging.filter.RequestLoggingFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import lombok.NonNull;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Explicit ERROR/ASYNC registration is essential: a filter annotation alone is insufficient.
 */
@Configuration(proxyBeanMethods = false)
public class RequestLoggingConfiguration {
    @Bean
    public FilterRegistrationBean<@NonNull RequestLoggingFilter> requestLoggingRegistration(
            RequestLoggingFilter filter
    ) {
        var registration = new FilterRegistrationBean<>(filter);

        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.ERROR
        );

        return registration;
    }

    @Bean
    public ServletListenerRegistrationBean<@NonNull ServletRequestListener> requestLoggingCompletionListener() {
        return new ServletListenerRegistrationBean<>(new ServletRequestListener() {
            @Override
            public void requestDestroyed(ServletRequestEvent event) {
                RequestLoggingFilter.completeRequest(event.getServletRequest());
            }
        });
    }
}
