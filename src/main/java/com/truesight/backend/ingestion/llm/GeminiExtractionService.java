package com.truesight.backend.ingestion.llm;

import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Evidence;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipType;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.CompanyResolutionService;
import com.truesight.backend.ingestion.sec.FetchedFiling;
import com.truesight.backend.repository.RelationshipRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates one filing's extraction end to end: call Gemini (with the AC 5.1 retry
 * policy), run the excerpt guard on every relationship's every excerpt, persist only
 * what passes, record an AuditLog entry regardless of outcome. This is the class that
 * makes the non-negotiable rules from the build brief actually load-bearing rather than
 * just implemented-somewhere: "No relationship without a passing source is ever shown"
 * is true because this method is the ONLY code path that creates Relationship/Evidence
 * rows from LLM output, and it never calls companyRepository.save/relationshipRepository
 * .save for a relationship whose excerpts all failed verification.
 *
 * <p><b>Transaction isolation, found the hard way:</b> both {@link #extractAndPersist}
 * and the audit-log write inside {@link #callOnce} use {@code Propagation.REQUIRES_NEW}
 * rather than the default REQUIRED. This was NOT the original design — it was added
 * after a real end-to-end test against a live server surfaced
 * {@code UnexpectedRollbackException}: when one holding's extraction fails inside
 * {@code PortfolioIngestionService#applyAndAnalyse}'s outer {@code @Transactional}
 * loop, the inner failed transaction (this class, under the default REQUIRED
 * propagation) gets marked rollback-only, and that mark is PHYSICALLY THE SAME
 * transaction as the outer loop's — so even though the outer loop's own try/catch
 * correctly catches the exception and sets the holding to FAILED, that update (and
 * every holding successfully analysed earlier in the same loop) gets silently
 * discarded when the outer transaction tries to commit and finds itself poisoned.
 * REQUIRES_NEW gives each holding's extraction (and each attempt's audit log entry)
 * its own independent transaction, so a failure is contained to exactly the row it
 * concerns — which is also the CORRECT semantic per AC 2.5 ("holdings already
 * analysed remain usable"): one holding's failure must never be able to roll back
 * another holding's success.
 */
@Service
public class GeminiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiExtractionService.class);

    private final GeminiClient geminiClient;
    private final ExcerptVerificationService excerptVerificationService;
    private final CompanyResolutionService companyResolutionService;
    private final RelationshipRepository relationshipRepository;
    private final AuditLogWriter auditLogWriter;
    private final LlmHealthTracker llmHealthTracker;

    public GeminiExtractionService(
            GeminiClient geminiClient,
            ExcerptVerificationService excerptVerificationService,
            CompanyResolutionService companyResolutionService,
            RelationshipRepository relationshipRepository,
            AuditLogWriter auditLogWriter,
            LlmHealthTracker llmHealthTracker
    ) {
        this.geminiClient = geminiClient;
        this.excerptVerificationService = excerptVerificationService;
        this.companyResolutionService = companyResolutionService;
        this.relationshipRepository = relationshipRepository;
        this.auditLogWriter = auditLogWriter;
        this.llmHealthTracker = llmHealthTracker;
    }

    /**
     * The result of one extraction attempt: how many relationships were extracted vs.
     * how many passed the excerpt guard, so the caller (IngestionService) can report
     * a meaningful coverage/progress message rather than a bare pass/fail.
     */
    public record ExtractionOutcome(int relationshipsExtracted, int relationshipsPersisted, int excerptsRejected) {
    }

    /**
     * AC 5.1: "The LLM output is validated against a JSON schema; malformed output is
     * retried once, then the holding is marked Failed." "Malformed" here covers any
     * exception from GeminiClient — a network failure, a non-200, or the API itself
     * rejecting output that didn't conform to the schema — all collapse to "this
     * attempt failed", and the SAME retry budget (one retry, total two attempts)
     * applies uniformly rather than trying to special-case which specific kind of
     * failure it was.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExtractionOutcome extractAndPersist(User requestingUser, Company filerCompany, FetchedFiling filing) {
        ExtractionResult result = callWithRetryPolicy(requestingUser, filerCompany, filing);

        applySectorAndCountryIfUnknown(filerCompany, result);

        int extracted = result.relationships().size();
        int persisted = 0;
        int rejected = 0;

        for (ExtractedRelationship extractedRel : result.relationships()) {
            List<String> verifiedExcerpts = new ArrayList<>();
            List<String> failedExcerpts = new ArrayList<>();
            for (ExtractedRelationship.ExtractedExcerpt excerpt : extractedRel.excerpts()) {
                if (excerptVerificationService.isVerbatim(excerpt.quotedText(), filing.fullText())) {
                    verifiedExcerpts.add(excerpt.quotedText());
                } else {
                    failedExcerpts.add(excerpt.quotedText());
                    rejected++;
                }
            }

            if (verifiedExcerpts.isEmpty()) {
                // Every excerpt for this relationship failed the guard — the whole
                // relationship is discarded, per AC 5.1: "Not found → discard the
                // relationship and log it." Logged at WARN (not silently dropped),
                // with enough detail to investigate without ever logging the excerpt
                // text itself at a level that could look like it was "shown".
                log.warn("Discarding relationship {} -> {} for filing {}: {} excerpt(s) failed verbatim "
                                + "verification against the fetched filing text",
                        filerCompany.getName(), extractedRel.counterpartyName(),
                        filing.accessionNumber(), failedExcerpts.size());
                continue;
            }

            persistRelationship(filerCompany, extractedRel, verifiedExcerpts, filing);
            persisted++;
        }

        return new ExtractionOutcome(extracted, persisted, rejected);
    }

    /** Base delay for exponential backoff: 1s, then 2s. Package-private so tests can zero it. */
    long backoffBaseMs = 1000;

    /**
     * Two acceptance criteria govern retries, and they are about different failures:
     * <ul>
     *   <li>AC 5.1: "malformed output is retried once, then the holding is marked
     *       Failed" — the model responded, but with something we couldn't parse.
     *       One retry, no delay (nothing to wait for).</li>
     *   <li>AC 9.2: "Retries use exponential backoff, at most 3 attempts" — the
     *       service was rate-limited or overloaded. Backing off is the whole point.</li>
     * </ul>
     * And a third case neither AC spells out but both imply: a bad API key, an
     * exhausted quota, or an unknown model name will not be fixed by trying again.
     * Retrying those wastes time and, for quota, possibly more quota — so they fail
     * on the first attempt. The policy is decided per failure KIND (see
     * LlmUnavailableException.Kind#isTransient), not by counting exceptions blindly.
     */
    private ExtractionResult callWithRetryPolicy(User requestingUser, Company filerCompany, FetchedFiling filing) {
        final int maxAttemptsTransient = 3;   // AC 9.2
        final int maxAttemptsMalformed = 2;   // AC 5.1: one retry
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxAttemptsTransient; attempt++) {
            try {
                return callOnce(requestingUser, filerCompany, filing, attempt);
            } catch (Exception e) {
                lastFailure = e;
                LlmUnavailableException llm = LlmUnavailableException.findIn(e);

                if (llm != null && !llm.getKind().isTransient()) {
                    log.info("Gemini extraction for {} failed with non-retryable {}; not retrying",
                            filerCompany.getName(), llm.getKind());
                    break;
                }

                boolean malformed = llm == null; // parse/schema failure rather than an HTTP-level one
                int maxAttempts = malformed ? maxAttemptsMalformed : maxAttemptsTransient;
                if (attempt >= maxAttempts) {
                    break;
                }

                if (llm != null) {
                    long delay = backoffBaseMs * (1L << (attempt - 1)); // 1s, 2s
                    log.info("Gemini extraction attempt {} for {} failed ({}); backing off {}ms before retry",
                            attempt, filerCompany.getName(), llm.getKind(), delay);
                    sleepQuietly(delay);
                } else {
                    log.info("Gemini extraction attempt {} for {} returned malformed output; retrying once (AC 5.1)",
                            attempt, filerCompany.getName());
                }
            }
        }
        throw new RuntimeException("Gemini extraction failed for " + filerCompany.getName(), lastFailure);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private ExtractionResult callOnce(User requestingUser, Company filerCompany, FetchedFiling filing, int attemptNumber)
            throws Exception {
        long startedAt = System.currentTimeMillis();
        try {
            ExtractionResult result = geminiClient.extractRelationships(filerCompany.getName(), filing.fullText());
            auditLogWriter.write(requestingUser, filerCompany, filing, geminiClient.currentModelName(), startedAt, true,
                    result.relationships().size() + " relationship(s) extracted", null);
            llmHealthTracker.recordSuccess();
            return result;
        } catch (Exception e) {
            // Written through auditLogWriter's OWN transaction (REQUIRES_NEW), not
            // inline here — see AuditLogWriter's Javadoc and this class's Javadoc for
            // why a plain @Transactional on a private method would silently not apply,
            // and why the failure entry must survive even when this attempt's own
            // transaction is about to be rolled back.
            auditLogWriter.write(requestingUser, filerCompany, filing, geminiClient.currentModelName(), startedAt, false, null, e.getMessage());
            llmHealthTracker.recordFailure(e);
            throw e;
        }
    }

    private void applySectorAndCountryIfUnknown(Company company, ExtractionResult result) {
        // AC 4.9: sector/country are LLM-assigned but never overwrite a user's manual
        // override, and a null result must never overwrite an already-known value with
        // "unknown" — this only fills gaps, never regresses known data to unknown.
        if (result.sector() != null && company.getSector() == null && !company.isSectorUserOverridden()) {
            company.setSector(result.sector());
        }
        if (result.country() != null && company.getCountry() == null) {
            company.setCountry(result.country());
        }
        company.touch();
    }

    private void persistRelationship(Company filerCompany, ExtractedRelationship extractedRel,
                                      List<String> verifiedExcerpts, FetchedFiling filing) {
        Company counterparty = companyResolutionService.resolveByName(extractedRel.counterpartyName());

        // "direction" in the extraction is relative to the FILER: SUPPLIER means the
        // counterparty supplies the filer, i.e. the edge goes counterparty -> filer.
        RelationshipType type = "CUSTOMER".equalsIgnoreCase(extractedRel.direction())
                ? RelationshipType.CUSTOMER : RelationshipType.SUPPLIER;
        Company fromCompany = (type == RelationshipType.SUPPLIER) ? counterparty : filerCompany;
        Company toCompany = (type == RelationshipType.SUPPLIER) ? filerCompany : counterparty;

        if (fromCompany.getId() != null && fromCompany.getId().equals(toCompany.getId())) {
            // AC 4.1: "self-loops are dropped." A company cannot supply or be a
            // customer of itself; this can only arise from a resolution collision
            // (the counterparty name resolved to the same Company as the filer).
            log.info("Dropping self-referential relationship for {} (extracted counterparty resolved to the same company)",
                    filerCompany.getName());
            return;
        }

        Relationship relationship = relationshipRepository
                .findByFromCompanyIdAndToCompanyIdAndRelationshipType(fromCompany.getId(), toCompany.getId(), type)
                .orElseGet(() -> new Relationship(fromCompany, toCompany, type));

        relationship.setCriticality(parseCriticality(extractedRel.criticality()));
        relationship.setDependencyPercent(extractedRel.dependencyPercent());
        relationship.setDisruptionReason(extractedRel.disruptionReason());
        relationship.touch();
        Relationship saved = relationshipRepository.save(relationship);

        for (String excerptText : verifiedExcerpts) {
            Evidence evidence = new Evidence(saved, filing.sourceType(), excerptText);
            evidence.setSourceDate(filing.filingDate());
            evidence.setSourceUrl(filing.documentUrl());
            evidence.setAccessionNumber(filing.accessionNumber());
            saved.getEvidence().add(evidence);
        }
    }

    private static Criticality parseCriticality(String raw) {
        try {
            return Criticality.valueOf(raw);
        } catch (Exception e) {
            return Criticality.DIVERSIFIED; // unstated/unparseable defaults to the least-alarming, most-honest label
        }
    }
}
