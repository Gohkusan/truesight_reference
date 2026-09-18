package com.truesight.backend.domain;

/**
 * AC 4.3's required dependency classification. SINGLE drives the dashed-edge visual
 * encoding in AC 4.4 and the confidence cap in AC 9.1 ("confidence is capped at Medium
 * for single-source relationships") — both keyed off this exact enum, so there is only
 * one place that decides "is this single-source", not a duplicated check per feature.
 */
public enum Criticality {
    SINGLE_SOURCE,
    DUAL_SOURCE,
    DIVERSIFIED
}
