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
        private final org.springframework.core.env.Environment environment;
        public SecurityConfig(org.springframework.core.env.Environment environment) { this.environment = environment; }


        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http,
                        ApiKeyAuthFilter apiKeyFilter) throws Exception {
                http
                                .csrf(AbstractHttpConfigurer::disable)
                                .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                                .authorizeHttpRequests(auth -> {
                                        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production", "prod", "shared-runner"))) {
                                                auth.requestMatchers("/api/v1/**", "/runs/**", "/h2-console/**", "/actuator/prometheus").denyAll();
                                        }
                                        auth
                                                // Public endpoints
                                                .requestMatchers(
                                                                "/swagger-ui/**", "/swagger-ui.html",
                                                                "/api-docs/**",
                                                                "/actuator/health", "/actuator/health/**", "/actuator/info", "/assets/**",
                                                                "/actuator/prometheus",
                                                                "/api/v1/tests/*/report", // Allow viewing HTML reports
                                                                                          // without API Key
                                                                "/runs/**", // Allow direct access to report assets
                                                                "/", "/index.html", "/ui/**",
                                                                "/platform-config.json", "/branding/**" // Public branding assets only
                                                ).permitAll()
                                                // Everything else requires API key
                                                .anyRequest().authenticated();
                                });
                if (!environment.getProperty("platform.security.service-token-file", "").isBlank()) {
                        http.exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, exception) -> response.sendError(401)));
                }
                return http.build();
        }
}
