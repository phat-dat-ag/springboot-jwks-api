package com.japaneselearning.jwks.metrics;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Extra unpublished connector preserves existing health/API paths on the application port.
 * All containers on the private Compose network are trusted scrapers. Never publish this port.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsEndpointConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "app.metrics.listener.enabled",
            havingValue = "true"
    )
    Connector metricsConnector(
            @Value("${app.metrics.listener.port:9091}")
            int port,

            @Value("${app.metrics.listener.address:0.0.0.0}")
            String address
    ) {
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Invalid metrics listener port");

        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);

        connector.setPort(port);
        if (!"0.0.0.0".equals(address)
                && !"127.0.0.1".equals(address)
        ) {
            throw new IllegalArgumentException("Unsupported metrics listener address");
        }

        connector.setProperty("address", address);
        connector.setProperty("maxThreads", "8");
        connector.setProperty("maxConnections", "32");
        connector.setProperty("acceptCount", "16");
        connector.setProperty("connectionTimeout", "5000");

        return connector;
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.metrics.listener.enabled",
            havingValue = "true"
    )
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> metricsListener(
            Connector metricsConnector
    ) {
        return factory -> factory.addAdditionalConnectors(metricsConnector);
    }

    @Bean
    @Order(-100)
    SecurityFilterChain metricsSecurity(HttpSecurity http, ObjectProvider<Connector> connectors) throws Exception {
        return http.securityMatcher(request -> onMetricsPort(request, connectors)
                        || request.getServletPath().equals("/actuator/prometheus")
                        || request.getServletPath().startsWith("/actuator/prometheus/"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(request -> onMetricsPort(request, connectors)
                                && request.getMethod().equals("GET")
                                && request.getServletPath().equals("/actuator/prometheus")).permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((
                                        request,
                                        response,
                                        failure
                                ) -> response.setStatus(403)
                        )
                        .accessDeniedHandler((
                                        request,
                                        response,
                                        failure
                                ) -> response.setStatus(403)
                        )
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable).build();
    }

    private static boolean onMetricsPort(
            HttpServletRequest request,
            ObjectProvider<Connector> connectors
    ) {
        Connector connector = connectors.getIfAvailable();

        return connector != null
                && connector.getLocalPort() > 0
                && request.getLocalPort() == connector.getLocalPort();
    }
}
