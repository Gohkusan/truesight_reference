package com.truesight.backend.domain;

/**
 * The user's judgement call on an AI-extracted relationship (AC 5.4: "confirm or
 * reject a relationship so that my judgement overrides the AI"). PENDING is the
 * default for everything the LLM produces; nothing is auto-confirmed, because
 * confirmation is specifically the human-in-the-loop signal this story exists to
 * capture.
 */
public enum ReviewStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}
