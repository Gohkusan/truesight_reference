package com.truesight.backend.ingestion.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truesight.backend.config.TrueSightProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only class that speaks to the Gemini API directly. Deliberately thin: builds the
 * request, sends it, parses the response into {@link ExtractionResult}. It does NOT
 * decide what to do with malformed output, does NOT run the excerpt guard, and does NOT
 * write audit log entries — those are GeminiExtractionService's job, one layer up. This
 * split mirrors SecEdgarClient/SecTickerIndexService: the "talk to the external API"
 * layer stays separate from the "apply this app's business rules to what came back"
 * layer, so either can be tested or replaced without touching the other.
 *
 * <p><b>Why structured JSON output (responseSchema) rather than prompting for JSON and
 * hoping:</b> Gemini's API supports constraining generation to a JSON schema server-
 * side. Asking nicely in a prompt for "respond in JSON" is a weaker guarantee — the
 * model can still wrap the JSON in prose, use inconsistent field names, or emit
 * invalid JSON under some inputs. A schema-constrained response is validated JSON by
 * construction; AC 5.1's "the LLM output is validated against a JSON schema" is
 * satisfied by the API itself rejecting non-conforming output, not by this app trying
 * to parse whatever came back and hoping for the best.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final TrueSightProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiClient(TrueSightProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.llm().timeoutSeconds()))
                .build();
    }

    public boolean isConfigured() {
        return properties.llm().isConfigured();
    }

    /** For audit-log entries (AC 9.3: "each entry records model name") without exposing the whole properties object. */
    public String currentModelName() {
        return properties.llm().model();
    }

    /**
     * Epic 7: classify one news article's relevance and sentiment for a company.
     * Same transport and schema-constrained output as extractRelationships; a much
     * smaller prompt. Throws LlmUnavailableException/IOException exactly as the
     * extraction call does, so callers reuse the same retry policy and banners.
     */
    public com.truesight.backend.ingestion.news.ArticleAnalysis analyzeArticle(String companyName, String title, String snippet)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            throw new LlmUnavailableException(LlmUnavailableException.Kind.NOT_CONFIGURED, "Gemini API key is not configured");
        }
        String prompt = """
                You are assessing whether a news item indicates a supply-chain disruption or risk event
                affecting %s (a company held in, or supplying, an equity portfolio).

                Headline: %s
                Snippet: %s

                Return:
                - relevance: 0.0 to 1.0 — how directly this concerns %s's ability to produce, source, or deliver.
                  0 if it is unrelated, about a different company, or generic market commentary.
                - sentiment: -1.0 (severe disruption: halt, shortage, sanction, recall, fire, strike) to
                  +1.0 (capacity expansion, resolved constraint). 0 for neutral.
                - category: one of OPERATIONAL_SHUTDOWN, GEOPOLITICAL_EXPORT_CURB, COMPONENT_SHORTAGE,
                  CAPACITY_EXPANSION, DEMAND_CATALYST, OTHER
                - summary: one plain sentence stating what happened and why it matters for %s. No advice.
                """.formatted(companyName, title, snippet == null ? "" : snippet, companyName, companyName);

        var root = objectMapper.createObjectNode();
        var parts = objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("text", prompt));
        root.set("contents", objectMapper.createArrayNode().add(objectMapper.createObjectNode().set("parts", parts)));
        var gen = objectMapper.createObjectNode();
        gen.put("responseMimeType", "application/json");
        gen.put("temperature", 0.1);
        var schema = objectMapper.createObjectNode();
        schema.put("type", "OBJECT");
        var props = objectMapper.createObjectNode();
        props.set("relevance", objectMapper.createObjectNode().put("type", "NUMBER"));
        props.set("sentiment", objectMapper.createObjectNode().put("type", "NUMBER"));
        props.set("category", enumSchema("OPERATIONAL_SHUTDOWN", "GEOPOLITICAL_EXPORT_CURB", "COMPONENT_SHORTAGE",
                "CAPACITY_EXPANSION", "DEMAND_CATALYST", "OTHER"));
        props.set("summary", stringSchema(false));
        schema.set("properties", props);
        schema.set("required", objectMapper.createArrayNode().add("relevance").add("sentiment").add("category").add("summary"));
        gen.set("responseSchema", schema);
        root.set("generationConfig", gen);

        String url = properties.llm().baseUrl() + "/models/" + properties.llm().model()
                + ":generateContent?key=" + properties.llm().apiKey();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(properties.llm().timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(root.toString())).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Gemini article analysis failed for {}: HTTP {}", companyName, response.statusCode());
            throw LlmUnavailableException.fromHttp(response.statusCode(), response.body());
        }
        JsonNode body = objectMapper.readTree(response.body());
        String text = body.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null);
        if (text == null) {
            throw new IOException("Gemini response candidate had no text content");
        }
        JsonNode a = objectMapper.readTree(text);
        return new com.truesight.backend.ingestion.news.ArticleAnalysis(
                a.path("relevance").asDouble(0), a.path("sentiment").asDouble(0),
                a.path("category").asText("OTHER"), a.path("summary").asText(""));
    }

    /**
     * One call to Gemini asking it to extract supply-chain relationships from a
     * filing's text, plus the filer's own sector/country. Throws on any failure
     * (network error, non-200, malformed/schema-rejected JSON) — retry policy lives in
     * the caller (GeminiExtractionService), per AC 5.1: "retried once, then the
     * holding is marked Failed". This method itself never retries, so the caller's
     * "at most one retry" is easy to verify by reading the caller alone.
     */
    public ExtractionResult extractRelationships(String companyName, String filingText) throws IOException, InterruptedException {
        if (!isConfigured()) {
            throw new LlmUnavailableException(LlmUnavailableException.Kind.NOT_CONFIGURED,
                    "Gemini API key is not configured");
        }

        String requestBody = buildRequestJson(companyName, filingText);
        String url = properties.llm().baseUrl() + "/models/" + properties.llm().model()
                + ":generateContent?key=" + properties.llm().apiKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(properties.llm().timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        log.debug("Calling Gemini ({}) for {}, filing text length={} chars", properties.llm().model(), companyName, filingText.length());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Deliberately logs only the status code and a truncated body, never the
            // request URL — the URL carries the API key as a query parameter (how
            // Google AI Studio's REST API expects it), and that must never reach a log
            // line, even at DEBUG level, even on failure.
            log.warn("Gemini request failed for {}: HTTP {}", companyName, response.statusCode());
            throw LlmUnavailableException.fromHttp(response.statusCode(), response.body());
        }

        return parseResponse(response.body());
    }

    private String buildRequestJson(String companyName, String filingText) {
        // Gemini has an input token ceiling; a full 10-K can be very large. Truncating
        // here (rather than sending the whole document and letting the API reject it)
        // is an honest, visible trade-off for a reference implementation — a
        // production system would chunk the filing and merge results across chunks,
        // which is real additional complexity this app does not attempt. What's sent
        // is still the actual fetched text, just capped, so the excerpt guard remains
        // valid: any excerpt the model returns is still checked against the full
        // original text, so truncation can only cause MISSED relationships (a false
        // negative), never a false positive that slips past verification.
        int maxChars = 60_000;
        String truncatedText = filingText.length() > maxChars ? filingText.substring(0, maxChars) : filingText;

        String prompt = """
                You are analysing an SEC filing for %s to identify its suppliers and customers.

                Read the filing text below and identify every company mentioned as a supplier
                (a company %s depends on for components, materials, or services) or a customer
                (a company that depends on %s, or that %s names as a significant customer).

                For EACH relationship you identify, you MUST include at least one EXACT quoted
                excerpt from the filing text below that supports it. Copy the excerpt exactly as
                it appears — do not paraphrase, summarise, or fix typos. If you cannot find an
                exact quote supporting a relationship, do not include that relationship.

                Also identify %s's own primary sector/industry and its principal country of
                business, ONLY if the filing text clearly states them. If unclear, leave those
                fields null — never guess.

                For criticality, use SINGLE_SOURCE only if the filing explicitly indicates there
                is no alternative supplier; DUAL_SOURCE if exactly two are named; DIVERSIFIED
                otherwise or if unstated.

                Filing text:
                ---
                %s
                ---
                """.formatted(companyName, companyName, companyName, companyName, companyName, truncatedText);

        // Built imperatively (not as one long chained expression) — Jackson's
        // ObjectNode/ArrayNode builder methods return different node types depending
        // on which overload of set()/put() is called, which makes a fully chained
        // construction of a nested structure like this hard to read correctly. A
        // handful of named local variables costs a few more lines and is worth it for
        // being obviously correct at a glance, matching this project's "favour the
        // obvious over the clever" guidance.
        var root = objectMapper.createObjectNode();
        var contents = objectMapper.createArrayNode();
        var content = objectMapper.createObjectNode();
        var parts = objectMapper.createArrayNode();
        parts.add(objectMapper.createObjectNode().put("text", prompt));
        content.set("parts", parts);
        contents.add(content);
        root.set("contents", contents);

        var generationConfig = objectMapper.createObjectNode();
        generationConfig.put("responseMimeType", "application/json");
        // temperature 0.2: deterministic-leaning, appropriate for factual extraction
        // rather than creative generation — the old prototype doc's own choice, and a
        // sound one for this task.
        generationConfig.put("temperature", 0.2);
        generationConfig.set("responseSchema", buildResponseSchema());
        root.set("generationConfig", generationConfig);

        return root.toString();
    }

    /**
     * Gemini's structured-output schema format (a constrained subset of OpenAPI
     * schema). This is what makes AC 5.1's "validated against a JSON schema" true at
     * the API level: Gemini will only emit JSON conforming to this shape, or the
     * request fails outright — there is no "the model tried but the JSON was slightly
     * off" middle ground to defend against here.
     */
    private JsonNode buildResponseSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "OBJECT");

        var properties = objectMapper.createObjectNode();

        properties.set("sector", stringSchema(true));
        properties.set("country", stringSchema(true));

        var relationshipItem = objectMapper.createObjectNode();
        relationshipItem.put("type", "OBJECT");
        var relProps = objectMapper.createObjectNode();
        relProps.set("counterpartyName", stringSchema(false));
        relProps.set("counterpartyTicker", stringSchema(true));
        relProps.set("direction", enumSchema("SUPPLIER", "CUSTOMER"));
        relProps.set("criticality", enumSchema("SINGLE_SOURCE", "DUAL_SOURCE", "DIVERSIFIED"));
        relProps.set("dependencyPercent", integerSchema(true));
        relProps.set("disruptionReason", stringSchema(true));

        var excerptItem = objectMapper.createObjectNode();
        excerptItem.put("type", "OBJECT");
        var excerptProps = objectMapper.createObjectNode();
        excerptProps.set("quotedText", stringSchema(false));
        excerptItem.set("properties", excerptProps);
        excerptItem.set("required", objectMapper.createArrayNode().add("quotedText"));

        var excerptsArray = objectMapper.createObjectNode();
        excerptsArray.put("type", "ARRAY");
        excerptsArray.set("items", excerptItem);
        relProps.set("excerpts", excerptsArray);

        relationshipItem.set("properties", relProps);
        relationshipItem.set("required", objectMapper.createArrayNode()
                .add("counterpartyName").add("direction").add("criticality").add("excerpts"));

        var relationshipsArray = objectMapper.createObjectNode();
        relationshipsArray.put("type", "ARRAY");
        relationshipsArray.set("items", relationshipItem);
        properties.set("relationships", relationshipsArray);

        schema.set("properties", properties);
        schema.set("required", objectMapper.createArrayNode().add("relationships"));
        return schema;
    }

    private JsonNode stringSchema(boolean nullable) {
        var node = objectMapper.createObjectNode();
        node.put("type", "STRING");
        if (nullable) {
            node.put("nullable", true);
        }
        return node;
    }

    private JsonNode integerSchema(boolean nullable) {
        var node = objectMapper.createObjectNode();
        node.put("type", "INTEGER");
        if (nullable) {
            node.put("nullable", true);
        }
        return node;
    }

    private JsonNode enumSchema(String... values) {
        var node = objectMapper.createObjectNode();
        node.put("type", "STRING");
        var enumArray = objectMapper.createArrayNode();
        for (String v : values) {
            enumArray.add(v);
        }
        node.set("enum", enumArray);
        return node;
    }

    private ExtractionResult parseResponse(String rawBody) throws IOException {
        JsonNode root = objectMapper.readTree(rawBody);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new IOException("Gemini response had no candidates: " + truncate(rawBody, 300));
        }
        String text = candidates.get(0).path("content").path("parts").path(0).path("text").asText(null);
        if (text == null) {
            throw new IOException("Gemini response candidate had no text content");
        }

        JsonNode extracted = objectMapper.readTree(text);
        String sector = extracted.path("sector").isNull() ? null : extracted.path("sector").asText(null);
        String country = extracted.path("country").isNull() ? null : extracted.path("country").asText(null);

        List<ExtractedRelationship> relationships = new ArrayList<>();
        for (JsonNode relNode : extracted.path("relationships")) {
            List<ExtractedRelationship.ExtractedExcerpt> excerpts = new ArrayList<>();
            for (JsonNode excerptNode : relNode.path("excerpts")) {
                String quoted = excerptNode.path("quotedText").asText(null);
                if (quoted != null && !quoted.isBlank()) {
                    excerpts.add(new ExtractedRelationship.ExtractedExcerpt(quoted));
                }
            }
            relationships.add(new ExtractedRelationship(
                    relNode.path("counterpartyName").asText(null),
                    relNode.path("counterpartyTicker").isNull() ? null : relNode.path("counterpartyTicker").asText(null),
                    relNode.path("direction").asText(null),
                    relNode.path("criticality").asText("DIVERSIFIED"),
                    relNode.path("dependencyPercent").isNull() ? null : relNode.path("dependencyPercent").asInt(),
                    relNode.path("disruptionReason").isNull() ? null : relNode.path("disruptionReason").asText(null),
                    excerpts
            ));
        }

        return new ExtractionResult(sector, country, relationships);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
