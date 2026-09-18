package com.truesight.backend.web.dto;

import com.truesight.backend.domain.AlertSeverity;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

/** AC 1.4 (profile) and AC 10.3 (alert preferences, watchlist). */
public final class SettingsDtos {

    private SettingsDtos() {
    }

    public record Profile(Long id, String email, String displayName, String firm, String alertEmail, Instant previousLoginAt) {
    }

    public record UpdateProfileRequest(String displayName, String firm, @Email String alertEmail) {
    }

    public record Preferences(AlertSeverity minimumAlertSeverity, List<String> eventTypes) {
    }

    public record UpdatePreferencesRequest(AlertSeverity minimumAlertSeverity, List<String> eventTypes) {
    }

    public record WatchlistEntryDto(Long id, Long companyId, String name, String ticker, Instant addedAt) {
    }

    public record AddWatchlistRequest(@NotBlank String ticker) {
    }
}
