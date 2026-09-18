package com.truesight.backend.ingestion.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses which part of a filing to send to the model when the whole document does
 * not fit. The first live extraction against a real 10-K (Microsoft's) came back with
 * zero relationships: the model was given the first 60,000 characters, which in a 10-K
 * is the cover page, forward-looking-statement boilerplate and the table of contents.
 * Supplier disclosures live in Item 1 (Business) and Item 1A (Risk Factors) for 10-K/
 * 10-Q, and Item 3.D / Item 4 for 20-F. This selector finds those sections by their
 * headings and sends them first, falling back to the document head only when no
 * heading is found.
 *
 * <p>Honest about its limits: heading detection is a regex over stripped text, and
 * filings vary. The table-of-contents lists the same headings, so the selector skips
 * matches that are followed within a few hundred characters by another "Item" heading
 * (a ToC entry is short; a real section is long). When it guesses wrong the result is
 * a worse prompt, never a false relationship — the verbatim guard still checks every
 * excerpt against the FULL text.
 */
public final class FilingSectionSelector {

    private FilingSectionSelector() {
    }

    // "Item 1." "ITEM 1A." "Item 3.D" etc. Case-insensitive; tolerates "Item 1A:" and "Item 1A —".
    private static final Pattern ITEM_HEADING = Pattern.compile(
            "(?im)^\\s*item\\s+(\\d{1,2}[A-D]?(?:\\.[A-D])?)\\s*[.:\\-–—]?\\s*([A-Za-z][^\\n]{0,80})?$");

    /** Preferred sections in priority order, per form family. */
    private static final List<String> PRIORITY_10K = List.of("1A", "1", "7");
    private static final List<String> PRIORITY_20F = List.of("3.D", "3", "4", "5");

    public static String select(String fullText, int budgetChars, boolean isForeignPrivateIssuer) {
        if (fullText == null) {
            return "";
        }
        if (fullText.length() <= budgetChars) {
            return fullText;
        }
        List<Section> sections = findSections(fullText);
        List<String> priority = isForeignPrivateIssuer ? PRIORITY_20F : PRIORITY_10K;

        StringBuilder out = new StringBuilder();
        for (String wanted : priority) {
            for (Section s : sections) {
                if (s.item.equalsIgnoreCase(wanted) && !s.looksLikeTocEntry) {
                    int remaining = budgetChars - out.length();
                    if (remaining <= 2000) {
                        return out.toString();
                    }
                    out.append("\n\n[Item ").append(s.item).append("]\n");
                    out.append(fullText, s.start, Math.min(s.end, s.start + remaining));
                    break; // first real occurrence of this item only
                }
            }
        }
        if (out.length() < budgetChars / 4) {
            // Headings not found (or tiny): fall back to the head of the document, but
            // skip an initial stretch that is almost always cover-page material.
            int skip = Math.min(fullText.length() / 10, 15_000);
            int end = Math.min(fullText.length(), skip + budgetChars);
            return fullText.substring(skip, end);
        }
        return out.toString();
    }

    record Section(String item, int start, int end, boolean looksLikeTocEntry) {
    }

    static List<Section> findSections(String text) {
        List<int[]> heads = new ArrayList<>(); // [start, item-end]
        List<String> items = new ArrayList<>();
        Matcher m = ITEM_HEADING.matcher(text);
        while (m.find()) {
            heads.add(new int[]{m.start(), m.end()});
            items.add(m.group(1).toUpperCase());
        }
        List<Section> out = new ArrayList<>();
        for (int i = 0; i < heads.size(); i++) {
            int start = heads.get(i)[1];
            int end = (i + 1 < heads.size()) ? heads.get(i + 1)[0] : text.length();
            boolean toc = (end - start) < 400; // a ToC line is followed almost immediately by the next Item
            out.add(new Section(items.get(i), start, end, toc));
        }
        return out;
    }
}
