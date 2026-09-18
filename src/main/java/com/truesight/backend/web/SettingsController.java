package com.truesight.backend.web;

import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.SettingsService;
import com.truesight.backend.web.dto.SettingsDtos.AddWatchlistRequest;
import com.truesight.backend.web.dto.SettingsDtos.Preferences;
import com.truesight.backend.web.dto.SettingsDtos.Profile;
import com.truesight.backend.web.dto.SettingsDtos.UpdatePreferencesRequest;
import com.truesight.backend.web.dto.SettingsDtos.UpdateProfileRequest;
import com.truesight.backend.web.dto.SettingsDtos.WatchlistEntryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC 1.4 and AC 10.3. Note what is NOT here: the LLM API key. It is server-side
 * config only and has no read or write endpoint anywhere in this API (Epic 1 note).
 */
@RestController
@RequestMapping("/api/settings")
@Tag(name = "Settings", description = "Profile, alert preferences, watchlist (AC 1.4, 10.3).")
public class SettingsController {

    private final SettingsService settingsService;
    private final CurrentUser currentUser;

    public SettingsController(SettingsService settingsService, CurrentUser currentUser) {
        this.settingsService = settingsService;
        this.currentUser = currentUser;
    }

    @GetMapping("/profile")
    @Operation(summary = "My profile")
    public Profile profile() {
        return settingsService.profile(currentUser.id());
    }

    @PutMapping("/profile")
    @Operation(summary = "Update name, firm, alert email (AC 1.4)")
    public Profile updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return settingsService.updateProfile(currentUser.id(), request);
    }

    @GetMapping("/preferences")
    @Operation(summary = "Alert preferences (AC 10.3)")
    public Preferences preferences() {
        return settingsService.preferences(currentUser.id());
    }

    @PutMapping("/preferences")
    @Operation(summary = "Set minimum alert severity and event types (AC 10.3)")
    public Preferences updatePreferences(@RequestBody UpdatePreferencesRequest request) {
        return settingsService.updatePreferences(currentUser.id(), request);
    }

    @GetMapping("/watchlist")
    @Operation(summary = "Companies I watch beyond my holdings (AC 10.3)")
    public List<WatchlistEntryDto> watchlist() {
        return settingsService.watchlist(currentUser.id());
    }

    @PostMapping("/watchlist")
    @Operation(summary = "Add a company by ticker to my watchlist")
    public WatchlistEntryDto addToWatchlist(@Valid @RequestBody AddWatchlistRequest request) {
        return settingsService.addToWatchlist(currentUser.id(), request.ticker());
    }

    @DeleteMapping("/watchlist/{entryId}")
    @Operation(summary = "Remove a watchlist entry")
    public ResponseEntity<Void> removeFromWatchlist(@PathVariable Long entryId) {
        settingsService.removeFromWatchlist(currentUser.id(), entryId);
        return ResponseEntity.noContent().build();
    }
}
