package com.truesight.backend.ingestion.news;

/**
 * Gemini's read of one article. relevance is 0-1 ("is this actually about a supply
 * disruption affecting this company?"); sentiment is -1 (severe disruption) to +1
 * (expansion/relief). Both feed the documented alert threshold in NewsIngestionService.
 */
public record ArticleAnalysis(double relevance, double sentiment, String category, String summary) {
}
