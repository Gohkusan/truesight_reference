package com.truesight.backend.domain;

/** AC 5.1: "Each source shows document type (10-K, 20-F, 8-K, news)...". */
public enum SourceType {
    FORM_10K,
    FORM_20F,
    FORM_10Q,
    FORM_6K,
    FORM_8K,
    NEWS
}
