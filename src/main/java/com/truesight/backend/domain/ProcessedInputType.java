package com.truesight.backend.domain;

/** AC 8.1: filings are keyed by SEC accession number, articles by URL — two different key spaces. */
public enum ProcessedInputType {
    SEC_FILING,
    NEWS_ARTICLE
}
