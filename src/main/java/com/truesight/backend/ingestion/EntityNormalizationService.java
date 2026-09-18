package com.truesight.backend.ingestion;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The first of the two non-negotiable hard parts called out in the build brief: "One
 * real company = one node... Strip legal suffixes, match to SEC CIK. 'TSMC', 'Taiwan
 * Semiconductor' and 'TSM' must produce one node. Everything downstream — shared-
 * supplier detection, node sizing, risk scores — is wrong without this."
 *
 * <p>This class does ONLY the string-normalisation half of that: given any raw name or
 * ticker string (from a CSV upload, an LLM extraction, or a filing's own supplier
 * mention), produce the canonical key that {@link com.truesight.backend.domain.Company}
 * is uniquely keyed on. It does NOT do CIK lookup or fuzzy alias resolution against the
 * SEC ticker index — that's {@code CompanyResolutionService}, which uses this class as
 * its first step and falls back to fuzzier matching only when an exact canonical-key
 * hit fails. Splitting these two concerns matters: normalisation is a pure function
 * (same input always produces the same output, no I/O, trivially unit-testable), while
 * resolution needs the ticker index and hits the database — keeping them separate means
 * the hard-to-get-wrong string logic can be tested exhaustively without any Spring
 * context or database at all.
 *
 * <p>The algorithm, in order — each step is a DECISION, not an arbitrary detail, so the
 * reasoning is recorded here rather than only in a commit message:
 * <ol>
 *   <li><b>Uppercase.</b> Case is never a meaningful distinction between two company
 *       names, and normalising it first means every later regex only needs one case
 *       to match.</li>
 *   <li><b>Strip non-alphanumeric characters and collapse whitespace FIRST</b>
 *       (periods, commas, ampersands, hyphens all become spaces; runs of spaces
 *       collapse to one). "AT&T" vs "AT & T" vs "AT-T" collapse to one token stream,
 *       and — importantly — this must happen BEFORE suffix stripping, not after: a
 *       suffix like the Dutch "N.V." only becomes the matchable token "N V" once its
 *       periods are gone. An earlier version of this method stripped suffixes first
 *       and silently failed to recognise "ASML Holding N.V."'s trailing suffix at
 *       all, leaving a dirty key — caught by a unit test, not by inspection.</li>
 *   <li><b>Strip a fixed list of legal-entity suffix TOKENS</b> (INC, CORP, LTD, LLC,
 *       PLC, SE, GMBH, HOLDINGS, GROUP, CO, COMPANY, NV, and more), matched against
 *       the now-clean, space-separated token stream. These carry no identity
 *       information — "Apple Inc" and "Apple" are the same company — and differ
 *       across filings, CSV uploads, and LLM phrasing for the exact same entity.</li>
 * </ol>
 *
 * <p>What this deliberately does NOT attempt: expanding abbreviations ("TSMC" does not
 * textually normalise to "TAIWAN SEMICONDUCTOR" — no string transform gets you from one
 * to the other). That gap is real and is exactly why CompanyResolutionService also
 * matches against the SEC ticker index by ticker symbol and by known alternate names,
 * not by canonical-key string matching alone. Canonicalisation solves "same name,
 * different formatting"; ticker/CIK resolution solves "different name, same company".
 * Both are needed; neither alone is enough.
 */
@Service
public class EntityNormalizationService {

    // Ordered longest-first within reason so "HOLDINGS CORP" doesn't leave a dangling
    // "CORP" that a later, narrower pattern would then also try to strip redundantly.
    // Every entry is matched only at the END of the (already-uppercased) name, with
    // optional trailing punctuation, so a suffix inside a real company name (e.g. a
    // hypothetical "Group Dynamics Inc") only strips the trailing legal-entity token,
    // never a word that happens to match a suffix mid-name.
    private static final List<String> LEGAL_SUFFIXES = List.of(
            "INCORPORATED", "CORPORATION", "COMPANY", "HOLDINGS", "HOLDING",
            "LIMITED", "GROUP", "TECHNOLOGIES", "TECHNOLOGY",
            "INC", "CORP", "CO", "LTD", "LLC", "LLP", "LP",
            "PLC", "SE", "GMBH", "AG",
            // "N V" (space-separated) listed ahead of "NV" (joined): after
            // punctuation-to-space normalisation, a source written "N.V." (the common
            // Dutch naamloze vennootschap form, periods included — e.g. "ASML Holding
            // N.V.") becomes the two tokens "N V", which the joined form "NV" alone
            // cannot match. A source already written "ASML NV" (no periods) matches
            // the joined form instead. Listing both covers both spellings; this is
            // exactly the kind of case that looks obviously right until a unit test
            // with a real filing-style name (ASML's actual registered name) catches it.
            "N V", "NV",
            "SA", "SPA", "BV", "AB", "AS", "OYJ",
            "KK", "KABUSHIKI KAISHA", "PTE", "PTY"
    );

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Z0-9 ]");
    private static final Pattern MULTI_SPACE = Pattern.compile(" {2,}");

    /**
     * Produces the canonical key {@link com.truesight.backend.domain.Company#getCanonicalKey()}
     * is stored and uniquely constrained on. Returns an empty string only for an
     * already-empty/blank input — callers (CompanyResolutionService) must treat that
     * as "cannot normalise this", not create a Company with a blank key.
     */
    public String canonicalize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }

        String working = rawName.trim().toUpperCase();

        // Punctuation is normalised to spaces FIRST, before suffix stripping — not
        // after. This matters for exactly cases like "ASML Holding N.V.": the Dutch
        // "N.V." suffix only becomes the matchable token "N V" once periods are gone,
        // and stripping suffixes against the still-punctuated string would miss it
        // (an earlier version of this method did suffix-stripping first and failed
        // on precisely this input — caught by EntityNormalizationServiceTest). Doing
        // punctuation cleanup first means every suffix in LEGAL_SUFFIXES only ever
        // needs to match the clean, space-separated token form.
        working = NON_ALPHANUMERIC.matcher(working).replaceAll(" ");
        working = MULTI_SPACE.matcher(working).replaceAll(" ").trim();

        // Strip suffixes iteratively: a name can legitimately carry more than one,
        // e.g. "Some Corp Holdings Inc" -> strip "INC" -> strip "HOLDINGS" -> "SOME CORP".
        // Bounded to LEGAL_SUFFIXES.size() passes so a pathological input can never
        // loop indefinitely even if a future suffix list edit introduces overlap.
        boolean strippedSomething = true;
        int passesRemaining = LEGAL_SUFFIXES.size();
        while (strippedSomething && passesRemaining-- > 0) {
            strippedSomething = false;
            for (String suffix : LEGAL_SUFFIXES) {
                String candidate = stripTrailingSuffix(working, suffix);
                if (candidate != null) {
                    working = candidate;
                    strippedSomething = true;
                    break; // restart the suffix scan against the shortened string
                }
            }
        }

        return working;
    }

    /**
     * Returns the string with a trailing legal-suffix TOKEN removed, or null if the
     * suffix does not match the trailing whitespace-delimited token. Operates on the
     * already space-normalised form (see canonicalize), so this only ever needs exact
     * token equality — no punctuation handling here. Token-based (not substring-based)
     * specifically so stripping "CO" from "SYSCO" is refused: "SYSCO" is one token that
     * happens to END in the letters "CO", not two tokens "SYS" + "CO", and collapsing
     * those would wrongly merge Sysco with any other name ending in "CO".
     */
    private String stripTrailingSuffix(String spaceNormalisedUpperName, String suffix) {
        String withoutSuffix = removeTrailingToken(spaceNormalisedUpperName, suffix);
        if (withoutSuffix == null) {
            return null;
        }
        return withoutSuffix.trim();
    }

    private String removeTrailingToken(String text, String token) {
        int tokenLen = token.length();
        int textLen = text.length();
        if (textLen < tokenLen || !text.regionMatches(textLen - tokenLen, token, 0, tokenLen)) {
            return null;
        }
        int boundary = textLen - tokenLen;
        boolean leftBoundaryOk = boundary == 0 || text.charAt(boundary - 1) == ' ';
        if (!leftBoundaryOk) {
            return null;
        }
        return text.substring(0, boundary);
    }
}
