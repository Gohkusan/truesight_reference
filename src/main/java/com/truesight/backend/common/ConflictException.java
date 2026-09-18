package com.truesight.backend.common;

/** A request is well-formed but violates a business rule, e.g. AC 1.1's duplicate email. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
