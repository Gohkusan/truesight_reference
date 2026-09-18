package com.truesight.backend.ingestion.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truesight.backend.config.TrueSightProperties;
import com.truesight.backend.domain.SourceType;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only class in this app that speaks to SEC EDGAR directly. Every request goes
 * through {@link SecRateLimiter} — see that class's Javadoc for why compliance is
 * structural rather than a convention.
 *
 * <p>Two SEC endpoints are used:
 * <ol>
 *   <li>{@code data.sec.gov/submissions/CIK##########.json} — a company's filing
 *       history metadata (form types, dates, accession numbers, document filenames).
 *       Cheap, no document text.</li>
 *   <li>{@code www.sec.gov/Archives/edgar/data/{cik}/{accession}/{document}} — the
 *       actual filing document, served as HTML. This is what gets stripped to plain
 *       text and handed to the verbatim excerpt guard.</li>
 * </ol>
 *
 * <p>Form-type scope is deliberately narrow: 10-K, 20-F, 10-Q, 6-K, 8-K — the forms
 * the old prototype doc identifies as carrying supply-chain-relevant disclosure (Item 1
 * Business / Item 1A Risk Factors for 10-K; Item 3.D for 20-F foreign private issuers;
 * material events for 8-K/6-K). Other form types (proxy statements, ownership filings)
 * are skipped even if present, since they essentially never discuss suppliers.
 */
@Component
public class SecEdgarClient {

    private static final Logger log = LoggerFactory.getLogger(SecEdgarClient.class);

    /** Newest-first priority when picking which single filing to analyse for a holding. */
    private static final List<String> RELEVANT_FORM_TYPES = List.of("10-K", "20-F", "10-Q", "6-K", "8-K");

    private final TrueSightProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecRateLimiter rateLimiter;

    public SecEdgarClient(TrueSightProperties properties, ObjectMapper objectMapper, SecRateLimiter rateLimiter) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * All filings for a company, newest first, restricted to RELEVANT_FORM_TYPES.
     * Empty list (not an exception) means "SEC has no relevant filings for this CIK" —
     * the caller (IngestionService) maps that to CoverageStatus.NO_SEC_FILINGS (AC 2.2),
     * distinct from a fetch actually failing.
     */
    public List<SecFilingSummary> listRecentFilings(String cik10) throws IOException, InterruptedException {
        String body = getWithRateLimit(
                properties.sec().submissionsBaseUrl() + "/CIK" + cik10 + ".json");
        JsonNode root = objectMapper.readTree(body);
        JsonNode recent = root.path("filings").path("recent");

        JsonNode forms = recent.path("form");
        JsonNode accessionNumbers = recent.path("accessionNumber");
        JsonNode filingDates = recent.path("filingDate");
        JsonNode primaryDocuments = recent.path("primaryDocument");

        List<SecFilingSummary> result = new ArrayList<>();
        int count = forms.size();
        for (int i = 0; i < count; i++) {
            String form = forms.get(i).asText("");
            if (!RELEVANT_FORM_TYPES.contains(form)) {
                continue;
            }
            String accession = accessionNumbers.get(i).asText("");
            LocalDate filingDate = LocalDate.parse(filingDates.get(i).asText());
            String primaryDoc = primaryDocuments.get(i).asText("");
            result.add(new SecFilingSummary(accession, form, filingDate, primaryDoc));
        }
        // SEC returns them newest-first already, but don't depend on that undocumented
        // ordering holding forever — sort explicitly.
        result.sort((a, b) -> b.filingDate().compareTo(a.filingDate()));
        return result;
    }

    /**
     * Which filing to analyse for supplier relationships. NOT simply the newest: 8-Ks
     * are filed for every earnings release and material event, so "newest relevant
     * filing" is nearly always an 8-K press release with no supplier disclosure in it —
     * the first live run analysed NVIDIA against a September 8-K and found nothing.
     * Supplier disclosure lives in the annual report (10-K, or 20-F for foreign
     * issuers), so that is preferred; a quarterly (10-Q/6-K) is the fallback; an 8-K is
     * used only when nothing else exists. Within a class, newest wins.
     */
    public static SecFilingSummary pickPrimaryFiling(List<SecFilingSummary> newestFirst) {
        for (List<String> tier : List.of(List.of("10-K", "20-F"), List.of("10-Q", "6-K"), List.of("8-K"))) {
            for (SecFilingSummary f : newestFirst) {
                if (tier.contains(f.formType())) {
                    return f;
                }
            }
        }
        return newestFirst.isEmpty() ? null : newestFirst.get(0);
    }

    /**
     * Fetches and returns the full plain-text content of one filing document. This is
     * the text ExcerptVerificationService searches against — nothing is summarised or
     * truncated here, because a passage the LLM quotes from later in the document must
     * still be findable.
     */
    public FetchedFiling fetchFilingText(String cik10, SecFilingSummary summary) throws IOException, InterruptedException {
        // SEC accession numbers are formatted with dashes in metadata (0000320193-24-
        // 000123) but the archive URL path segment uses them WITHOUT dashes.
        String accessionNoDashes = summary.accessionNumber().replace("-", "");
        // CIK in the archive path is NOT zero-padded (unlike the submissions endpoint,
        // which wants the 10-digit padded form) — SEC's own inconsistency between the
        // two endpoints, not a bug here.
        String cikUnpadded = String.valueOf(Long.parseLong(cik10));
        String documentUrl = properties.sec().archivesBaseUrl() + "/" + cikUnpadded + "/"
                + accessionNoDashes + "/" + summary.primaryDocument();

        String cached = readDiskCache(summary.accessionNumber());
        String html = cached != null ? cached : getWithRateLimit(documentUrl);
        if (cached == null) {
            writeDiskCache(summary.accessionNumber(), html);
        }

        String plainText = stripHtmlToText(html);
        return new FetchedFiling(
                summary.accessionNumber(),
                mapFormTypeToSourceType(summary.formType()),
                summary.filingDate(),
                documentUrl,
                plainText
        );
    }

    private String getWithRateLimit(String url) throws IOException, InterruptedException {
        rateLimiter.acquire();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", properties.sec().userAgent())
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("SEC EDGAR request failed: HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } finally {
            rateLimiter.release();
        }
    }

    // ---- disk cache: raw HTML by accession number, so a re-analysis of an already-
    // seen filing (a second holding that shares a supplier's filing, or a retry) never
    // re-hits SEC's network. Distinct from ProcessedInput (which tracks "did we already
    // run EXTRACTION on this"), this is a lower-level "do we already have the bytes".

    private String readDiskCache(String accessionNumber) {
        Path path = filingCachePath(accessionNumber);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("Failed to read filing cache at {}, will re-fetch", path, e);
            return null;
        }
    }

    private void writeDiskCache(String accessionNumber, String html) {
        try {
            Path path = filingCachePath(accessionNumber);
            Files.createDirectories(path.getParent());
            Files.writeString(path, html);
        } catch (IOException e) {
            log.warn("Failed to write filing cache for accession {}", accessionNumber, e);
        }
    }

    private Path filingCachePath(String accessionNumber) {
        return Path.of(properties.sec().cacheDir(), "filings", accessionNumber + ".html");
    }

    private static SourceType mapFormTypeToSourceType(String formType) {
        return switch (formType) {
            case "10-K" -> SourceType.FORM_10K;
            case "20-F" -> SourceType.FORM_20F;
            case "10-Q" -> SourceType.FORM_10Q;
            case "6-K" -> SourceType.FORM_6K;
            case "8-K" -> SourceType.FORM_8K;
            default -> throw new IllegalArgumentException("Unmapped SEC form type: " + formType);
        };
    }

    // ---- HTML -> plain text -----------------------------------------------------
    // SEC filings are served as full HTML documents (often with embedded CSS/JS,
    // financial-statement tables, XBRL tagging noise). A real HTML parser (jsoup)
    // would be the production choice; this reference implementation uses a
    // deliberately simple, dependency-free regex strip instead, documented here so a
    // team studying this knows exactly what it does and doesn't handle — see the
    // Javadoc on stripHtmlToText for the specific trade-off.

    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "<(script|style)\\b[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");

    private static final Set<String> BLOCK_TAGS_NEEDING_NEWLINE = Set.of(
            "p", "div", "br", "tr", "li", "h1", "h2", "h3", "h4", "h5", "h6");

    /**
     * A simple, honest strip: remove script/style blocks entirely, replace block-level
     * tags with a newline (so "Item 1. Business" and its following paragraph don't get
     * jammed onto one run-on line), strip all remaining tags, unescape the handful of
     * HTML entities SEC filings actually use, and collapse whitespace.
     *
     * <p>What this deliberately does NOT do: render tables into any structured text,
     * resolve nested/malformed HTML the way a real parser would, or handle every HTML
     * entity (only the common ones: &amp;amp; &amp;nbsp; &amp;lt; &amp;gt; &amp;quot;
     * &amp;#39;). Table cell contents still come through as text, just without column
     * structure — acceptable for THIS app's purpose (finding a quoted sentence
     * verbatim), unacceptable if a future feature needed to reason about a financial
     * statement's row/column structure. If that need arises, replace this with jsoup
     * (a single, well-understood dependency) rather than extending this regex further.
     */
    public static String stripHtmlToText(String html) {
        String withoutScripts = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");

        StringBuilder withNewlines = new StringBuilder(withoutScripts.length());
        int i = 0;
        while (i < withoutScripts.length()) {
            char c = withoutScripts.charAt(i);
            if (c == '<') {
                int end = withoutScripts.indexOf('>', i);
                if (end == -1) {
                    break;
                }
                String tagContent = withoutScripts.substring(i + 1, end).toLowerCase();
                String tagName = tagContent.replaceFirst("^/", "").split("[\\s/]")[0];
                if (BLOCK_TAGS_NEEDING_NEWLINE.contains(tagName)) {
                    withNewlines.append('\n');
                }
                i = end + 1;
            } else {
                withNewlines.append(c);
                i++;
            }
        }

        String noTags = TAG.matcher(withNewlines).replaceAll("");
        String unescaped = unescapeCommonEntities(noTags);
        String collapsedSpaces = MULTI_WHITESPACE.matcher(unescaped).replaceAll(" ");
        String collapsedNewlines = MULTI_NEWLINE.matcher(collapsedSpaces).replaceAll("\n\n");
        return collapsedNewlines.trim();
    }

    private static String unescapeCommonEntities(String text) {
        return text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&rsquo;", "’")
                .replace("&lsquo;", "‘")
                .replace("&rdquo;", "”")
                .replace("&ldquo;", "“");
    }
}
