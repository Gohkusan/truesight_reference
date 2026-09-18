package com.truesight.backend.common;

/**
 * Thrown for BOTH "this id doesn't exist" and "this id exists but isn't yours" — the
 * two cases are deliberately indistinguishable at this layer. AC 1.3: "Requesting a
 * portfolio, holding, alert, or graph belonging to another account returns 404 (not
 * 403, to avoid leaking existence)". A 403 would confirm the resource exists; a 404
 * that always looks the same whether the row is absent or just not yours leaks
 * nothing. Every service method that loads an owned resource throws this on a failed
 * id+ownerId lookup — see the repository Javadocs in the id+ownerId query pattern.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
