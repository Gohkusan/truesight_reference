package com.truesight.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single persona this whole app is built for (AM = Asset Manager). One role only,
 * per the spec's note in Epic 1: role differentiation is satisfied cheaply with one
 * ASSET_MANAGER role and everything scoped to the logged-in account, rather than
 * building out a real RBAC system for a course project with one persona.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt hash. Never the raw password, never logged, never returned in a DTO. */
    @Column(nullable = false)
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    private String firm;

    /**
     * Where alert digests go (AC 1.4, AC 7.5). Defaults to the login email but is
     * editable separately, because some managers want alerts on a distribution list
     * rather than their personal login address.
     */
    @Column(name = "alert_email")
    private String alertEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * Timestamp of the previous login, captured at the START of the current session
     * (i.e. this is always "the login before this one"). Drives the "since your last
     * visit" summary (AC 3.4) — without this, that story can't compute "since when".
     */
    @Column(name = "previous_login_at")
    private Instant previousLoginAt;

    protected User() {
        // JPA
    }

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.alertEmail = email;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getFirm() {
        return firm;
    }

    public void setFirm(String firm) {
        this.firm = firm;
    }

    public String getAlertEmail() {
        return alertEmail;
    }

    public void setAlertEmail(String alertEmail) {
        this.alertEmail = alertEmail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPreviousLoginAt() {
        return previousLoginAt;
    }

    public void setPreviousLoginAt(Instant previousLoginAt) {
        this.previousLoginAt = previousLoginAt;
    }
}
