package com.truesight.backend.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The one place that reads "who is making this request" out of Spring Security's
 * context. Every controller asks this for the current user id rather than reading
 * SecurityContextHolder directly, so the authentication mechanism (JWT today) is
 * swappable without touching every controller.
 */
@Component
public class CurrentUser {

    public Long id() {
        return principal().id();
    }

    public UserPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            // Should be unreachable for any endpoint SecurityConfig marks authenticated —
            // Spring Security itself returns 401 before the controller is invoked if
            // getAuthentication() would be null/anonymous here. This guards the
            // programmer error of calling CurrentUser from an accidentally-public endpoint.
            throw new IllegalStateException("No authenticated user in this request context");
        }
        return principal;
    }
}
