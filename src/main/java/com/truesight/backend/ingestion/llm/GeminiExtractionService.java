package com.truesight.backend.ingestion.llm;

import com.truesight.backend.domain.AuditActionType;
import com.truesight.backend.domain.AuditLog;
import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Evidence;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipType;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.CompanyResolutionService;
import com.truesight.backend.ingestion.sec.FetchedFiling;
import com.truesight.backend.repository.AuditLogRepository;
import com.truesight.backend.repository.RelationshipRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
 */
@Service
public class GeminiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiExtractionService.class);

    private final GeminiClient geminiClient;
    private final ExcerptVerificationService excerptVerificationService;
    private final CompanyResolutionService companyResolutionService;
    private final RelationshipRepository relationshipRepository;
    private final AuditLogRepository auditLogRepository;

    public GeminiExtractionService(
            GeminiClient geminiClient,
            ExcerptVerificationService excerptVerificationService,
            CompanyResolutionService companyResolutionService,
            RelationshipRepository relationshipRepository,
            AuditLogRepository auditLogRepository
    ) {
        this.geminiClient = geminiClient;
        this.excerptVerificationService = excerptVerificationService;
        this.companyResolutionService = companyResolutionService;
        this.relationshipRepository = relationshipRepository;
        this.auditLogRepository = auditLogRepository;
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
    @Transactional
    public ExtractionOutcome extractAndPersist(User requestingUser, Company filerCompany, FetchedFiling filing) {
        ExtractionResult result = callWithOneRetry(requestingUser, filerCompany, filing);

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

    private ExtractionResult callWithOneRetry(User requestingUser, Company filerCompany, FetchedFiling filing) {
        // Written as "try attempt 1, then on failure try attempt 2 and either return
        // or throw immediately" rather than a bounded loop with a lastFailure variable
        // set inside a catch block — that shape requires the reader (and the
        // compiler's null-flow analysis) to trust that the loop body always executes
        // and always assigns before the post-loop throw. This shape makes the AC 5.1
        // policy ("one retry, total two attempts") visible directly in the control
        // flow: there are exactly two call sites of extractRelationships below, one
        // per attempt, nothing to count.
        try {
            return callOnce(requestingUser, filerCompany, filing, 1);
        } catch (Exception firstFailure) {
            log.info("Gemini extraction attempt 1 failed for {}, retrying once (AC 5.1 policy): {}",
                    filerCompany.getName(), firstFailure.getMessage());
            try {
                return callOnce(requestingUser, filerCompany, filing, 2);
            } catch (Exception secondFailure) {
                throw new RuntimeException(
                        "Gemini extraction failed after 1 retry for " + filerCompany.getName(), secondFailure);
            }
        }
    }

    private ExtractionResult callOnce(User requestingUser, Company filerCompany, FetchedFiling filing, int attemptNumber)
            throws Exception {
        long startedAt = System.currentTimeMillis();
        try {
            ExtractionResult result = geminiClient.extractRelationships(filerCompany.getName(), filing.fullText());
            writeAuditLog(requestingUser, filerCompany, filing, startedAt, true,
                    result.relationships().size() + " relationship(s) extracted", null);
            return result;
        } catch (Exception e) {
            writeAuditLog(requestingUser, filerCompany, filing, startedAt, false, null, e.getMessage());
            throw e;
        }
    }

    private void writeAuditLog(User user, Company target, FetchedFiling filing, long startedAtMs,
                                boolean success, String outputSummary, String errorMessage) {
        AuditLog entry = new AuditLog(user, AuditActionType.SEC_FILING_EXTRACTION, target.getName(), geminiModelNameForAudit());
        entry.setInputReference("Filing accession " + filing.accessionNumber() + " (" + filing.sourceType() + ", " + filing.filingDate() + ")");
        entry.setOutputSummary(outputSummary);
        entry.setLatencyMs(System.currentTimeMillis() - startedAtMs);
        entry.setSuccess(success);
        entry.setErrorMessage(errorMessage);
        auditLogRepository.save(entry);
    }

    private String geminiModelNameForAudit() {
        // A thin accessor rather than reaching into GeminiClient's internals from here —
        // keeps GeminiExtractionService from needing to know GeminiClient's properties
        // wiring, just that it can report which model it used.
        return geminiClient.currentModelName();
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
