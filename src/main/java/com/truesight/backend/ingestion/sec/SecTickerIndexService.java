package com.truesight.backend.ingestion.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truesight.backend.config.TrueSightProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SEC's ticker→CIK→name directory (company_tickers.json), ~10,000 entries. Per the
 * build brief: "Keep the SEC ticker index cache on disk" — deliberately NOT a JPA
 * entity/table. This is read-only reference data published by a third party (SEC), not
 * data this app owns or mutates; loading it into memory from a flat JSON cache file is
 * both simpler than a database table (no migration, no repository, no per-row entity
 * overhead for 10,000 rows of static data) and closer to what it actually is —a
 * downloaded snapshot of someone else's directory, refreshed periodically, not
 * application state.
 *
 * <p>Loaded once at startup ({@code @PostConstruct}), held in memory for the life of
 * the process, refreshable on demand via {@link #refresh()}. A ReentrantReadWriteLock
 * guards the in-memory maps: many concurrent lookups (read lock, cheap, unlimited
 * concurrency) against an occasional full-index replace (write lock, exclusive) during
 * a refresh — the common case (lookup) is optimised, the rare case (refresh) is simply
 * correct.
 */
@Service
public class SecTickerIndexService {

    private static final Logger log = LoggerFactory.getLogger(SecTickerIndexService.class);

    private final TrueSightProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Map<String, SecTickerIndexEntry> byTicker = new HashMap<>();
    private Map<String, SecTickerIndexEntry> byCik = new HashMap<>();
    private List<SecTickerIndexEntry> all = new ArrayList<>();
    private Instant lastLoadedAt;

    public SecTickerIndexService(TrueSightProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @PostConstruct
    public void loadOnStartup() {
        try {
            loadFromDiskCacheOrFetch();
        } catch (Exception e) {
            // A missing/unreachable ticker index at startup must not crash the whole
            // app — it degrades ticker lookup (AC 2.1/2.3 features), not authentication
            // or portfolio viewing. Logged loudly so it's not silently invisible.
            log.error("Failed to load SEC ticker index at startup; ticker lookup will be unavailable until "
                    + "refresh() succeeds", e);
        }
    }

    public Optional<SecTickerIndexEntry> findByTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byTicker.get(ticker.trim().toUpperCase()));
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<SecTickerIndexEntry> findByCik(String cik10) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(byCik.get(cik10));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** AC 2.3: "Search-as-you-type against the SEC ticker index shows ticker and company name." */
    public List<SecTickerIndexEntry> searchByPrefix(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String needle = query.trim().toUpperCase();
        lock.readLock().lock();
        try {
            return all.stream()
                    .filter(e -> e.ticker().startsWith(needle) || e.companyName().toUpperCase().contains(needle))
                    .limit(limit)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isLoaded() {
        lock.readLock().lock();
        try {
            return !byTicker.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Instant getLastLoadedAt() {
        return lastLoadedAt;
    }

    /** Forces a fresh fetch from SEC, bypassing the disk cache. */
    public void refresh() throws IOException, InterruptedException {
        fetchAndCache();
    }

    private void loadFromDiskCacheOrFetch() throws IOException, InterruptedException {
        Path cacheFile = cacheFilePath();
        if (Files.isRegularFile(cacheFile)) {
            log.info("Loading SEC ticker index from disk cache at {}", cacheFile);
            JsonNode root = objectMapper.readTree(cacheFile.toFile());
            applyIndex(parseSecTickerJson(root));
            return;
        }
        fetchAndCache();
    }

    private void fetchAndCache() throws IOException, InterruptedException {
        log.info("Fetching SEC ticker index from {}", properties.sec().tickerIndexUrl());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.sec().tickerIndexUrl()))
                .header("User-Agent", properties.sec().userAgent())
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("SEC ticker index fetch failed: HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        List<SecTickerIndexEntry> entries = parseSecTickerJson(root);
        applyIndex(entries);

        Path cacheFile = cacheFilePath();
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, response.body());
        log.info("Cached SEC ticker index ({} entries) to {}", entries.size(), cacheFile);
    }

    /**
     * SEC's company_tickers.json has an unusual shape: a JSON OBJECT whose keys are
     * stringified array indices ("0", "1", "2", ...) rather than a JSON array — an
     * artifact of how it's generated server-side. Each value is {"cik_str": <int>,
     * "ticker": "AAPL", "title": "Apple Inc."}. Handled here rather than assumed,
     * because a naive "treat this as a JSON array" parse would silently read zero
     * entries against this actual shape.
     */
    private List<SecTickerIndexEntry> parseSecTickerJson(JsonNode root) {
        List<SecTickerIndexEntry> entries = new ArrayList<>();
        root.properties().forEach(field -> {
            JsonNode value = field.getValue();
            int cik = value.path("cik_str").asInt(-1);
            String ticker = value.path("ticker").asText(null);
            String title = value.path("title").asText(null);
            if (cik >= 0 && ticker != null && title != null) {
                entries.add(new SecTickerIndexEntry(ticker.toUpperCase(), padCik(cik), title));
            }
        });
        return entries;
    }

    private static String padCik(int cik) {
        return String.format("%010d", cik);
    }

    private void applyIndex(List<SecTickerIndexEntry> entries) {
        Map<String, SecTickerIndexEntry> newByTicker = new HashMap<>();
        Map<String, SecTickerIndexEntry> newByCik = new HashMap<>();
        for (SecTickerIndexEntry entry : entries) {
            newByTicker.put(entry.ticker(), entry);
            newByCik.put(entry.cik10(), entry);
        }
        lock.writeLock().lock();
        try {
            this.byTicker = newByTicker;
            this.byCik = newByCik;
            this.all = List.copyOf(entries);
            this.lastLoadedAt = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Path cacheFilePath() {
        return Path.of(properties.sec().cacheDir(), "company_tickers.json");
    }
}
