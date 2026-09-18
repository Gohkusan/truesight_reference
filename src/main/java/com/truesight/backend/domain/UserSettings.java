package com.truesight.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * AC 10.3: "choose event types, a risk threshold, and a watchlist of companies so that
 * alerts match my needs." One row per user (1:1, not per-portfolio) because alert
 * preferences are how one analyst wants to be notified, not a property of any single
 * book of holdings — the same person applies the same tolerance for noise whichever
 * portfolio triggered the alert.
 *
 * <p>eventTypes is a comma-separated string of AlertSeverity-adjacent category tags
 * rather than a normalised join table, matching the same "display/filter-only list,
 * never individually queried" reasoning used for RiskAssessment.factorsJson — a small
 * fixed vocabulary read as a whole, not joined against.
 */
@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /**
     * Minimum severity that generates a visible alert (AC 7.4: "set a minimum
     * severity so that I only see what matters"). Alerts below this are still stored
     * (so raising the threshold later doesn't lose history) but filtered from the
     * default alert feed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "minimum_alert_severity", nullable = false)
    private AlertSeverity minimumAlertSeverity = AlertSeverity.LOW;

    /** Comma-separated event-type tags the user wants alerts for, e.g. "operational,geopolitical". */
    @Column(name = "event_types", length = 500)
    private String eventTypes;

    protected UserSettings() {
        // JPA
    }

    public UserSettings(User user) {
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public AlertSeverity getMinimumAlertSeverity() {
        return minimumAlertSeverity;
    }

    public void setMinimumAlertSeverity(AlertSeverity minimumAlertSeverity) {
        this.minimumAlertSeverity = minimumAlertSeverity;
    }

    public String getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(String eventTypes) {
        this.eventTypes = eventTypes;
    }
}
