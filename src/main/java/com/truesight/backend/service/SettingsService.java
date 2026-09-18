package com.truesight.backend.service;

import com.truesight.backend.common.NotFoundException;
import com.truesight.backend.common.ValidationException;
import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.User;
import com.truesight.backend.domain.UserSettings;
import com.truesight.backend.domain.WatchlistEntry;
import com.truesight.backend.ingestion.CompanyResolutionService;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.repository.UserSettingsRepository;
import com.truesight.backend.repository.WatchlistEntryRepository;
import com.truesight.backend.web.dto.SettingsDtos.Preferences;
import com.truesight.backend.web.dto.SettingsDtos.Profile;
import com.truesight.backend.web.dto.SettingsDtos.UpdatePreferencesRequest;
import com.truesight.backend.web.dto.SettingsDtos.UpdateProfileRequest;
import com.truesight.backend.web.dto.SettingsDtos.WatchlistEntryDto;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AC 1.4 profile, AC 10.3 alert preferences and watchlist. All keyed on the caller's own user id. */
@Service
public class SettingsService {

    private final UserRepository userRepository;
    private final UserSettingsRepository settingsRepository;
    private final WatchlistEntryRepository watchlistRepository;
    private final CompanyResolutionService companyResolutionService;

    public SettingsService(UserRepository userRepository, UserSettingsRepository settingsRepository,
                           WatchlistEntryRepository watchlistRepository, CompanyResolutionService companyResolutionService) {
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.watchlistRepository = watchlistRepository;
        this.companyResolutionService = companyResolutionService;
    }

    @Transactional(readOnly = true)
    public Profile profile(Long userId) {
        User u = user(userId);
        return new Profile(u.getId(), u.getEmail(), u.getDisplayName(), u.getFirm(), u.getAlertEmail(), u.getPreviousLoginAt());
    }

    @Transactional
    public Profile updateProfile(Long userId, UpdateProfileRequest req) {
        User u = user(userId);
        if (req.displayName() != null) {
            u.setDisplayName(req.displayName().isBlank() ? null : req.displayName().trim());
        }
        if (req.firm() != null) {
            u.setFirm(req.firm().isBlank() ? null : req.firm().trim());
        }
        if (req.alertEmail() != null && !req.alertEmail().isBlank()) {
            u.setAlertEmail(req.alertEmail().trim());
        }
        return profile(userId);
    }

    @Transactional(readOnly = true)
    public Preferences preferences(Long userId) {
        UserSettings s = settingsOrDefault(userId);
        return new Preferences(s.getMinimumAlertSeverity(), splitEventTypes(s.getEventTypes()));
    }

    @Transactional
    public Preferences updatePreferences(Long userId, UpdatePreferencesRequest req) {
        UserSettings s = settingsOrDefault(userId);
        if (req.minimumAlertSeverity() != null) {
            s.setMinimumAlertSeverity(req.minimumAlertSeverity());
        }
        if (req.eventTypes() != null) {
            s.setEventTypes(String.join(",", req.eventTypes()));
        }
        settingsRepository.save(s);
        return preferences(userId);
    }

    @Transactional(readOnly = true)
    public List<WatchlistEntryDto> watchlist(Long userId) {
        return watchlistRepository.findByUserId(userId).stream().map(SettingsService::toDto).toList();
    }

    @Transactional
    public WatchlistEntryDto addToWatchlist(Long userId, String ticker) {
        Company company = companyResolutionService.resolveByTicker(ticker)
                .orElseThrow(() -> new ValidationException("Ticker \"" + ticker + "\" was not found in the SEC index"));
        WatchlistEntry entry = watchlistRepository.findByUserIdAndCompanyId(userId, company.getId())
                .orElseGet(() -> watchlistRepository.save(new WatchlistEntry(user(userId), company)));
        return toDto(entry);
    }

    @Transactional
    public void removeFromWatchlist(Long userId, Long entryId) {
        WatchlistEntry entry = watchlistRepository.findById(entryId)
                .filter(e -> e.getUser().getId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Watchlist entry not found"));
        watchlistRepository.delete(entry);
    }

    private User user(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private UserSettings settingsOrDefault(Long userId) {
        return settingsRepository.findByUserId(userId).orElseGet(() -> settingsRepository.save(new UserSettings(user(userId))));
    }

    private static List<String> splitEventTypes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static WatchlistEntryDto toDto(WatchlistEntry e) {
        return new WatchlistEntryDto(e.getId(), e.getCompany().getId(), e.getCompany().getName(),
                e.getCompany().getTicker(), e.getAddedAt());
    }
}
