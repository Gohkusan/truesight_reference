package com.truesight.backend.ingestion.llm;

import java.util.List;

/**
 * The full parsed response for one filing's extraction pass: the filer's own
 * sector/country (AC 4.9), plus every relationship it disclosed. sector/country are
 * nullable — AC 4.9: "where it cannot be determined the node is coloured unknown and
 * is never guessed", so a null here must reach the Company entity as null, never a
 * fallback string.
 */
public record ExtractionResult(
        String sector,
        String country,
        List<ExtractedRelationship> relationships
) {
}
