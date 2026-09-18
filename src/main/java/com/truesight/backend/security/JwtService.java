package com.truesight.backend.security;

import com.truesight.backend.config.TrueSightProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.springframework.stereotype.Service;

/**
 * Issues and validates JWTs. Why JWT rather than server-side sessions (a genuine choice
 * worth explaining, not just narrating the code): this is a stateless REST API with no
 * server-side view rendering, so a session would need its own store (in-memory, which
 * breaks on restart and can't scale past one instance, or a DB-backed session table,
 * which is more moving parts for no benefit here). A signed JWT lets any endpoint
 * validate a request with zero database lookup — the token itself carries the user id,
 * and the signature proves it wasn't tampered with. The trade-off we're accepting is
 * that a JWT can't be revoked before it expires (no server-side "log out everywhere"),
 * which is fine for a course-project reference implementation with a short expiry
 * (AC 1.2's "session token expires after a set time") and would be worth revisiting
 * with a refresh-token/deny-list scheme in a real deployment.
 */
@Service
public class JwtService {

    private final Key signingKey;
    private final int expirationMinutes;

    public JwtService(TrueSightProperties properties) {
        // HMAC-SHA256 needs a key of at least 256 bits (32 bytes). The secret in
        // application.yml is a plain string; hashing it here (rather than requiring the
        // operator to hand-generate exactly 32 random bytes) means any reasonably long
        // passphrase works safely as TRUESIGHT_JWT_SECRET.
        byte[] rawKeyBytes = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(ensureMinLength(rawKeyBytes));
        this.expirationMinutes = properties.jwt().expirationMinutes();
    }

    private static byte[] ensureMinLength(byte[] key) {
        if (key.length >= 32) {
            return key;
        }
        // Pad a too-short dev secret rather than crashing at startup; a real deployment
        // is expected to set a properly long TRUESIGHT_JWT_SECRET (see .env.example).
        byte[] padded = new byte[32];
        System.arraycopy(key, 0, padded, 0, key.length);
        return padded;
    }

    public String issueToken(Long userId, String email) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the authenticated user id if the token is valid and unexpired, or empty
     * otherwise. Deliberately swallows all JWT exceptions into "not authenticated"
     * rather than propagating them — an expired or malformed token is a normal,
     * expected condition (AC 1.2: "expired token redirects to login"), not a server
     * error, so the filter that calls this should get a clean signal to act on.
     */
    public java.util.Optional<Long> validateAndGetUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith((javax.crypto.SecretKey) signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return java.util.Optional.of(Long.valueOf(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException already covers ExpiredJwtException (its subclass) along with
            // malformed/unsigned/tampered tokens; IllegalArgumentException covers a
            // null/empty token string. Both collapse to the same "not authenticated"
            // outcome, per this method's contract above.
            return java.util.Optional.empty();
        }
    }
}
