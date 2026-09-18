package com.truesight.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Typed view over the {@code truesight.*} tree in application.yml. One class per
 * concern (Jwt, Llm, Sec, News, Ingestion) as nested records, so each service depends
 * only on the slice it actually uses rather than importing one giant properties bag —
 * the same separation-by-concern the workstreams are organised around.
 */
@ConfigurationProperties(prefix = "truesight")
public record TrueSightProperties(
        @NestedConfigurationProperty Jwt jwt,
        @NestedConfigurationProperty Llm llm,
        @NestedConfigurationProperty Sec sec,
        @NestedConfigurationProperty News news,
        @NestedConfigurationProperty Ingestion ingestion
) {

    public record Jwt(String secret, int expirationMinutes) {
    }

    /**
     * apiKey is intentionally the only secret-shaped field anywhere in this properties
     * tree. It is read once here, at startup, from environment/.env — never accepted
     * as a request parameter, never returned in any response DTO. Grep the web package
     * for "apiKey" if you need to convince yourself of that while rebuilding this.
     */
    public record Llm(String provider, String apiKey, String model, String baseUrl,
                       int timeoutSeconds, int maxRetries) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Sec(String userAgent, long minRequestIntervalMs, int maxConcurrentRequests,
                       String cacheDir, String tickerIndexUrl, String submissionsBaseUrl,
                       String archivesBaseUrl) {
    }

    public record News(String googleNewsRssBase, String cacheDir, double relevanceThreshold) {
    }

    public record Ingestion(String cacheDir, int maxParallelHoldings) {
    }
}
