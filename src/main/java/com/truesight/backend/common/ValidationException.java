package com.truesight.backend.common;

/**
 * A request failed a business-level validation rule that isn't expressible as a
 * jakarta.validation annotation on a DTO field — e.g. AC 2.1's CSV-specific rejections
 * ("non-CSV file", "missing ticker column", "zero data rows"), where the rule depends
 * on parsing the file's actual content, not just its shape.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
