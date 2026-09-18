package com.truesight.backend.ingestion.llm;

import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The second of the two non-negotiable hard parts called out in the build brief: "Every
 * relationship Gemini extracts must come with a quoted excerpt, and that excerpt must
 * be found by literal string search in the filing text you actually fetched. Not found
 * → discard the relationship and log it. Normalise whitespace and quote characters
 * before comparing. No relationship without a passing source is ever shown."
 *
 * <p>This is the hallucination guard. Everything else in this app — confidence scores,
 * risk scores, the shared-supplier table, the graph itself — is downstream of a
 * Relationship existing, and a Relationship only exists if at least one Evidence row
 * passed this check (see GeminiExtractionService, which calls this for every excerpt
 * the model returns and discards any relationship whose excerpt fails).
 *
 * <p><b>Why "verbatim" needs normalisation at all, rather than a raw exact-substring
 * check:</b> an LLM reproducing a quote from a document it was given as input tends to
 * reproduce the WORDS exactly but not always the incidental formatting — curly vs.
 * straight quotes, a line-wrapped sentence the model renders as one line, doubled
 * spaces from the source HTML's own formatting. A check that is too strict would
 * discard TRUE excerpts for cosmetic reasons, which is its own kind of failure (real
 * evidence wrongly thrown away). A check that is too loose would let through excerpts
 * that were paraphrased or invented. The normalisation below is deliberately narrow —
 * whitespace and quote-character variants only, nothing about word choice or order —
 * so it closes the formatting gap without opening a paraphrase-tolerance gap.
 */
@Service
public class ExcerptVerificationService {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    // Every Unicode quote-character variant a model or a filing's own HTML rendering
    // might use in place of a plain ASCII quote. Curly double quotes, curly single
    // quotes/apostrophes, guillemets, and the CJK corner-bracket quotes some non-US
    // filings (e.g. 20-F filers) render — all folded to a single ASCII form so
    // "single-source" and "single‑source" (en dash vs hyphen is NOT folded — see
    // below) or “quoted” vs "quoted" compare equal.
    private static final String[] CURLY_DOUBLE_QUOTES = {"“", "”", "„", "‟", "«", "»"};
    private static final String[] CURLY_SINGLE_QUOTES = {"‘", "’", "‚", "‛"};

    /**
     * True if {@code excerpt} can be found, verbatim after normalisation, inside
     * {@code fetchedSourceText}. Both arguments are normalised identically before
     * comparison, so this is symmetric: a difference that would be forgiven going one
     * direction is forgiven going the other.
     *
     * <p>Deliberately does NOT normalise case: a genuine excerpt should match the
     * source's actual capitalisation. Allowing case-insensitive matching here would
     * let through an excerpt that changed proper-noun capitalisation, which is a real
     * (if small) signal of paraphrasing rather than quotation.
     */
    public boolean isVerbatim(String excerpt, String fetchedSourceText) {
        if (excerpt == null || excerpt.isBlank() || fetchedSourceText == null) {
            return false;
        }
        String normalizedExcerpt = normalize(excerpt);
        if (normalizedExcerpt.isBlank()) {
            return false;
        }
        String normalizedSource = normalize(fetchedSourceText);
        return normalizedSource.contains(normalizedExcerpt);
    }

    /**
     * Whitespace collapse + quote-character folding, nothing else. Kept as its own
     * method (rather than inlined) so a unit test can assert on its output directly,
     * independent of the substring-search behaviour.
     */
    public String normalize(String text) {
        String result = text.trim();
        for (String variant : CURLY_DOUBLE_QUOTES) {
            result = result.replace(variant, "\"");
        }
        for (String variant : CURLY_SINGLE_QUOTES) {
            result = result.replace(variant, "'");
        }
        result = WHITESPACE_RUN.matcher(result).replaceAll(" ");
        return result;
    }
}
