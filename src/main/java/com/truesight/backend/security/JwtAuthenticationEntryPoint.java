package com.truesight.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truesight.backend.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * AC 1.2: "Every data endpoint returns 401 without a valid token." Spring Security's
 * default behaviour for a request that authorizeHttpRequests rejects is 403 Forbidden
 * (its AccessDeniedHandler), which is the wrong status here: 403 means "we know who you
 * are and you're not allowed"; 401 means "we don't know who you are". Every rejection
 * this app produces for a missing/expired/malformed token IS the second case, so this
 * entry point overrides the default to always return 401 with the same ErrorResponse
 * shape GlobalExceptionHandler uses everywhere else — consistent error handling across
 * both request-processing failures (that class) and pre-authentication failures (this
 * one).
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(401, "Unauthorized", "Authentication required");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
