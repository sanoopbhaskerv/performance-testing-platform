package com.perfplatform.security;

import com.perfplatform.config.TeamConfig;
import com.perfplatform.config.TeamConfigLoader;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates the X-API-Key header against the apiKey defined in each team's
 * config.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final TeamConfigLoader configLoader;

    public ApiKeyAuthFilter(TeamConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");

        if (apiKey != null) {
            boolean valid = configLoader.getAllConfigs().stream()
                    .map(TeamConfig::getApiKey)
                    .anyMatch(apiKey::equals);

            if (valid) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(apiKey, null,
                        List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }
}
