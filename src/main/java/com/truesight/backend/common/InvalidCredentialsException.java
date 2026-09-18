package com.truesight.backend.common;

/**
 * AC 1.2: "Wrong email or password returns ONE generic 'invalid credentials' message."
 * A single exception type (never "email not found" vs "wrong password" as distinct
 * cases) so that distinction can never leak through error handling into two different
 * messages — the account-enumeration protection is structural, not a discipline the
 * caller has to remember to apply.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
