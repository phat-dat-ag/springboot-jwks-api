package com.japaneselearning.jwks.config;

import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.GET,
                                "/.well-known/jwks.json",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml"
                        ).permitAll()

                        // Preserve Boot's existing public health endpoints and authenticated fallback.
                        .requestMatchers(
                                EndpointRequest.to(HealthEndpoint.class),
                                EndpointRequest.toAdditionalPaths(
                                        WebServerNamespace.SERVER,
                                        HealthEndpoint.class
                                )
                        ).permitAll()

                        .anyRequest().authenticated())

                .cors(withDefaults())
                .formLogin(withDefaults())
                .httpBasic(withDefaults())
                .build();
    }
}