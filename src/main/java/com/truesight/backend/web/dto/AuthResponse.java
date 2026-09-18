package com.truesight.backend.web.dto;

/**
 * Never includes passwordHash — this is the boundary that guarantees a hash can never
 * accidentally leak into a response, because the DTO that crosses that boundary simply
 * has no field for it to occupy.
 */
public record AuthResponse(String token, Long userId, String email, String displayName) {
}
