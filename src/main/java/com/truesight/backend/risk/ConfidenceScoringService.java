package com.truesight.backend.risk;

import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Evidence;
import com.truesight.backend.domain.Relationship;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * AC 5.2: "one confidence score per relationship with a plain explanation... The same
 * score is used everywhere in the app. There is no second 'AI confidence' number."
 *
 * <p>This is the ONLY place a confidence number is computed. It is stored on
 * Relationship.confidenceScore by RiskScoringService and read from there by every
 * screen; nothing else recomputes it. The formula is deliberately additive and
 * inspectable — every term below is also returned as a factor string so the tooltip
 * (AC 5.2: "lists the inputs: number of independent sources, recency of the latest
 * source, whether the filing names the counterparty explicitly") can show exactly the
 * arithmetic that produced the number, rather than a number and a hand-wave.
 *
 * <p>Caps, both from the spec:
 * <ul>
 *   <li>AC 9.1: single-source relationships are capped at MEDIUM. "Single source" here
 *       means ONE independent filing (one accession number) backs it, regardless of
 *       how many excerpts that filing contributed.</li>
 *   <li>AC 5.5: when sources conflict, confidence is capped at MEDIUM.</li>
 * </ul>
 */
@Service
public class ConfidenceScoringService {

    public static final int HIGH_THRESHOLD = 75;
    public static final int MEDIUM_THRESHOLD = 45;
    /** The highest score that still reads as MEDIUM; caps land here. */
    public static final int MEDIUM_CEILING = HIGH_THRESHOLD - 1;

    public enum Band { HIGH, MEDIUM, LOW }

    public record Result(int score, Band band, List<String> factors, boolean singleSource) {
    }

    public static Band bandOf(int score) {
        if (score >= HIGH_THRESHOLD) {
            return Band.HIGH;
        }
        if (score >= MEDIUM_THRESHOLD) {
            return Band.MEDIUM;
        }
        return Band.LOW;
    }

    public Result score(Relationship relationship, LocalDate today) {
        List<String> factors = new ArrayList<>();
        List<Evidence> evidence = relationship.getEvidence();

        int score = 40;
        factors.add("Base: 40");

        // Independent sources = distinct filings, not distinct excerpts. Three quotes
        // from one 10-K are one source; the same fact in a 10-K and a later 10-Q is two.
        Set<String> distinctSources = new HashSet<>();
        for (Evidence e : evidence) {
            distinctSources.add(e.getAccessionNumber() != null ? e.getAccessionNumber() : String.valueOf(e.getSourceUrl()));
        }
        int sourceCount = distinctSources.size();
        boolean singleSource = sourceCount <= 1;
        int sourceBonus = Math.min(30, Math.max(0, sourceCount - 1) * 20);
        if (sourceBonus > 0) {
            score += sourceBonus;
            factors.add(sourceCount + " independent sources: +" + sourceBonus);
        } else {
            factors.add("1 source (single source)");
        }

        LocalDate latest = evidence.stream()
                .map(Evidence::getSourceDate)
                .filter(d -> d != null)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (latest != null) {
            long months = ChronoUnit.MONTHS.between(latest, today);
            if (months <= 12) {
                score += 20;
                factors.add("Latest source within 12 months (" + latest + "): +20");
            } else if (months <= 24) {
                score += 10;
                factors.add("Latest source within 24 months (" + latest + "): +10");
            } else {
                factors.add("Latest source older than 24 months (" + latest + "): +0");
            }
        } else {
            factors.add("No dated source: +0");
        }

        // "Names the counterparty explicitly": the counterparty resolved to an SEC
        // registrant (has a CIK), i.e. the filing named a real, identifiable company
        // rather than "a supplier in Asia".
        boolean explicitlyNamed = counterpartyOf(relationship) != null;
        if (explicitlyNamed) {
            score += 10;
            factors.add("Counterparty is an identified SEC registrant: +10");
        } else {
            factors.add("Counterparty not matched to an SEC registrant: +0");
        }

        score = Math.min(100, score);

        if (singleSource && score > MEDIUM_CEILING) {
            score = MEDIUM_CEILING;
            factors.add("Capped at Medium: single source (AC 9.1)");
        }
        if (relationship.isSourcesConflict() && score > MEDIUM_CEILING) {
            score = MEDIUM_CEILING;
            factors.add("Capped at Medium: sources conflict (AC 5.5)");
        }

        return new Result(score, bandOf(score), factors, singleSource);
    }

    /** The non-filer end of the edge, if it has a CIK; null otherwise. Filer side is unknown here, so check both. */
    private static String counterpartyOf(Relationship r) {
        String from = r.getFromCompany().getCik();
        String to = r.getToCompany().getCik();
        // Both ends being SEC registrants is the strong case; either end missing a CIK
        // means at least one party was only a name in prose.
        return (from != null && to != null) ? from : null;
    }

    /** Convenience for callers that only need the criticality-independent single-source flag. */
    public static boolean isSingleSourceCriticality(Relationship r) {
        return r.getCriticality() == Criticality.SINGLE_SOURCE;
    }
}
