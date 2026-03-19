package com.perfplatform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * API key-based security. Each team is issued an API key (from their team
 * config).
 * All /api/v1/** endpoints require a valid X-API-Key header.
 * Swagger UI and actuator/health are public.
 */
@Configuration
public class SecurityConfig {

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http,
                        ApiKeyAuthFilter apiKeyFilter) throws Exception {
                http
                                .csrf(AbstractHttpConfigurer::disable)
                                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                                .authorizeHttpRequests(auth -> auth
                                                // Public endpoints
                                                .requestMatchers(
                                                                "/swagger-ui/**", "/swagger-ui.html",
                                                                "/api-docs/**",
                                                                "/actuator/health", "/actuator/info",
                                                                "/actuator/prometheus",
                                                                "/api/v1/tests/*/report", // Allow viewing HTML reports
                                                                                          // without API Key
                                                                "/runs/**", // Allow direct access to report assets
                                                                "/", "/index.html", "/ui/**" // Web UI
                                                ).permitAll()
                                                // Everything else requires API key
                                                .anyRequest().authenticated());
                return http.build();
        }
}
