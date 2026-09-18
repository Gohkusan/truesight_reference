package com.truesight.backend.web;

import com.truesight.backend.service.AuthService;
import com.truesight.backend.web.dto.AuthResponse;
import com.truesight.backend.web.dto.LoginRequest;
import com.truesight.backend.web.dto.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only controller reachable without a token (see SecurityConfig's permitAll for
 * /api/auth/**). Logout is deliberately NOT an endpoint here: with stateless JWTs there
 * is no server-side session to invalidate, so "log out" (AC 1.2) is entirely a
 * frontend action — discard the stored token. See JwtService's Javadoc for the
 * trade-off that comes with this choice.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration and login. No token required.")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new account", description = "AC 1.1: email + password (min 8 chars), duplicate email rejected, logs the user in immediately on success.")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "AC 1.2: wrong email or password returns one generic 'invalid credentials' message.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
