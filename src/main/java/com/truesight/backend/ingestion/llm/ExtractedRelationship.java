package com.truesight.backend.ingestion.llm;

import java.util.List;

/**
 * One relationship as returned by Gemini, deserialised from its JSON response, BEFORE
 * the verbatim excerpt guard has run. This is intentionally a distinct type from the
 * domain Relationship/Evidence entities — everything here is unverified model output,
 * and keeping it a separate shape means there is no code path by which an unverified
 * excerpt could accidentally be assigned to a Relationship/Evidence row without
 * passing through GeminiExtractionService's verification step first.
 */
public record ExtractedRelationship(
        String counterpartyName,
        String counterpartyTicker, // best-guess ticker/CIK hint, may be null — see CompanyResolutionService's Javadoc
        String direction, // "SUPPLIER" or "CUSTOMER" — this company's relationship TO the filer
        String criticality, // "SINGLE_SOURCE" | "DUAL_SOURCE" | "DIVERSIFIED"
        Integer dependencyPercent, // 0-100, nullable
        String disruptionReason,
        List<ExtractedExcerpt> excerpts
) {

    public record ExtractedExcerpt(String quotedText) {
    }
}
