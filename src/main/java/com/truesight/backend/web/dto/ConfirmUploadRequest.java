package com.truesight.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * AC 2.1: "unrecognised... can be skipped or corrected" — excludedTickers is how the
 * frontend tells the backend which previewed rows the user chose to drop before
 * applying (typically the unrecognised ones, but not necessarily limited to those).
 *
 * <p>AC 2.6: removeMissing=true makes this a re-upload that replaces the book:
 * holdings present now but absent from the CSV are soft-removed (undoable, AC 2.4).
 * Holdings that remain are updated IN PLACE, which is what preserves their reviewed
 * marks, dismissed alerts and relationship overrides — those all key off the
 * company/relationship, not the CSV row. Default false: an upload only adds/updates.
 */
public record ConfirmUploadRequest(
        @NotBlank String csvContent,
        List<String> excludedTickers,
        Boolean removeMissing
) {
    public boolean removeMissingOrDefault() {
        return Boolean.TRUE.equals(removeMissing);
    }
}
