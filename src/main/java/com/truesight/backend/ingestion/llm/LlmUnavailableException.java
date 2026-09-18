package com.truesight.backend.ingestion.llm;

/**
 * A typed failure from the LLM API, classified by KIND rather than left as a raw
 * IOException with an HTTP status buried in its message. AC 9.2: "LLM errors, rate
 * limits, and budget exhaustion each show a distinct banner" — that is only possible
 * if the failure carries its category as data the rest of the app can switch on, not
 * as prose it would have to regex out of an error string.
 */
public class LlmUnavailableException extends RuntimeException {

    public enum Kind {
        /** Missing or rejected API key. Configuration problem, not transient. */
        NOT_CONFIGURED,
        /** HTTP 429 without quota language: back off and retry later. */
        RATE_LIMITED,
        /** Free-tier daily/monthly quota exhausted. Retrying sooner won't help. */
        BUDGET_EXHAUSTED,
        /** The configured model name is unknown/retired. Fix GEMINI_MODEL. */
        MODEL_NOT_FOUND,
        /** HTTP 502/503/504: the service itself is overloaded. Retry with backoff. */
        TEMPORARILY_UNAVAILABLE,
        /** Anything else: network failure, other 5xx, malformed response. */
        ERROR;

        /**
         * Whether a retry could plausibly succeed. Drives GeminiExtractionService's
         * retry policy: retrying a bad API key or an exhausted quota only burns time
         * (and, for quota, possibly more quota), so those fail fast.
         */
        public boolean isTransient() {
            return this == RATE_LIMITED || this == TEMPORARILY_UNAVAILABLE || this == ERROR;
        }
    }

    private final Kind kind;

    public LlmUnavailableException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public LlmUnavailableException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind getKind() {
        return kind;
    }

    /** Short, user-facing sentence for a holding's failureReason or a banner. */
    public String userFacingSummary() {
        return switch (kind) {
            case NOT_CONFIGURED -> "AI service is not configured (no API key)";
            case RATE_LIMITED -> "AI service rate limit reached; try again in a few minutes";
            case BUDGET_EXHAUSTED -> "AI service quota exhausted for this billing period";
            case MODEL_NOT_FOUND -> "Configured AI model is not available; check GEMINI_MODEL";
            case TEMPORARILY_UNAVAILABLE -> "AI service is temporarily overloaded; retry later";
            case ERROR -> "AI service error: " + getMessage();
        };
    }

    /** Classifies an HTTP status + response body from the Gemini API. */
    public static LlmUnavailableException fromHttp(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase();
        Kind kind;
        if (status == 401 || status == 403) {
            kind = Kind.NOT_CONFIGURED;
        } else if (status == 429) {
            // Google returns 429 for both burst rate limits and exhausted free-tier
            // quota; the body distinguishes them ("quota" / "exceeded your current
            // quota"). Budget exhaustion must not be shown as "try again in a minute".
            kind = lower.contains("quota") ? Kind.BUDGET_EXHAUSTED : Kind.RATE_LIMITED;
        } else if (status == 404) {
            kind = Kind.MODEL_NOT_FOUND;
        } else if (status == 502 || status == 503 || status == 504) {
            kind = Kind.TEMPORARILY_UNAVAILABLE;
        } else {
            kind = Kind.ERROR;
        }
        String snippet = body == null ? "" : body.replaceAll("\\s+", " ");
        if (snippet.length() > 300) {
            snippet = snippet.substring(0, 300) + "...";
        }
        return new LlmUnavailableException(kind, "Gemini HTTP " + status + ": " + snippet);
    }

    /** Finds the nearest LlmUnavailableException in a cause chain, or null. */
    public static LlmUnavailableException findIn(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof LlmUnavailableException llm) {
                return llm;
            }
            current = current.getCause();
        }
        return null;
    }
}
