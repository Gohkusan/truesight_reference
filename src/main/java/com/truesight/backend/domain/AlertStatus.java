package com.truesight.backend.domain;

/** AC 7.3: track what has been handled (reviewed) versus dismissed as noise. */
public enum AlertStatus {
    NEW,
    REVIEWED,
    DISMISSED,
    /** AC 2.4 / 7.1: the holding it was tied to was removed from the portfolio. */
    ARCHIVED
}
