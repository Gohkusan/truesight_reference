package com.truesight.backend.ingestion.news;

import com.truesight.backend.config.TrueSightProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Fetches recent supply-chain news for one company from Google News RSS. This is
 * the only class that talks to Google News; the query shape (company name plus
 * supply-chain keywords, last 7 days) is the old prototype doc's, kept because it
 * produces relevant hits without a paid news API.
 *
 * <p>The XML parser is hardened against XXE: external entities and DTDs are disabled.
 * RSS from a third party is untrusted input, and a parser with defaults would happily
 * resolve an external entity declared in a malicious feed.
 */
@Component
public class GoogleNewsClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleNewsClient.class);
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final TrueSightProperties properties;
    private final HttpClient httpClient;

    public GoogleNewsClient(TrueSightProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public List<NewsArticle> fetchRecent(String companyName) throws IOException, InterruptedException {
        String query = "\"" + companyName + "\" (supply chain OR factory OR production OR shortage OR supplier OR disruption) when:7d";
        String url = properties.news().googleNewsRssBase() + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&hl=en-US&gl=US&ceid=US:en";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", properties.sec().userAgent())
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Google News RSS returned HTTP " + response.statusCode());
        }
        return parseRss(response.body());
    }

    List<NewsArticle> parseRss(byte[] xml) throws IOException {
        List<NewsArticle> out = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml));
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String title = text(item, "title");
                String link = text(item, "link");
                String pubDate = text(item, "pubDate");
                String source = text(item, "source");
                String description = text(item, "description");
                if (title == null || link == null) {
                    continue;
                }
                Instant published = parseDate(pubDate);
                out.add(new NewsArticle(stripSourceSuffix(title), link, source, published, description));
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse RSS: " + e.getMessage(), e);
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        if (n.getLength() == 0) {
            return null;
        }
        String s = n.item(0).getTextContent();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Instant parseDate(String pubDate) {
        if (pubDate == null) {
            return Instant.now();
        }
        try {
            return ZonedDateTime.parse(pubDate, RFC_1123).toInstant();
        } catch (Exception e) {
            log.debug("Unparseable pubDate '{}', using now", pubDate);
            return Instant.now();
        }
    }

    /** Google News titles end " - Outlet Name"; the outlet is also in <source>, so drop it from the title. */
    private static String stripSourceSuffix(String title) {
        int idx = title.lastIndexOf(" - ");
        return idx > 20 ? title.substring(0, idx).trim() : title;
    }
}
