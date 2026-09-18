package com.truesight.backend.ingestion.news;

import java.time.Instant;

/** One RSS item, as fetched. url is the dedupe key AC 8.1 names ("articles by URL"). */
public record NewsArticle(String title, String url, String outlet, Instant publishedAt, String snippet) {
}
