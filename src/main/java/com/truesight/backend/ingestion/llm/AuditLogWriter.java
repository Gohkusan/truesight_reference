package com.truesight.backend.ingestion.llm;

import com.truesight.backend.domain.AuditActionType;
import com.truesight.backend.domain.AuditLog;
import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.sec.FetchedFiling;
import com.truesight.backend.repository.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one AuditLog row in its OWN transaction, independent of whatever extraction
 * attempt it is recording. A separate bean, not a private method on
 * GeminiExtractionService, because Spring's proxy-based {@code @Transactional} only
 * takes effect on a call THROUGH the proxy — a private method called from within the
 * same class bypasses the proxy entirely and the annotation would be silently
 * ignored. This is the standard, correct way around that: extract the piece that
 * needs its own transaction boundary into a separate Spring bean, so the call from
 * GeminiExtractionService genuinely goes through this bean's proxy.
 *
 * <p>Why REQUIRES_NEW specifically (not the default REQUIRED): an audit log entry for
 * a FAILED attempt must still be written even though the surrounding extraction
 * attempt is about to be rolled back — "audit every AI action" (AC 9.3) explicitly
 * includes failures, and a failure entry that gets rolled back along with the failure
 * it describes would defeat the entire purpose of an audit trail for exactly the
 * incidents it exists to help diagnose.
 */
@Component
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    public AuditLogWriter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(User user, Company target, FetchedFiling filing, String modelName, long startedAtMs,
                       boolean success, String outputSummary, String errorMessage) {
        writeGeneric(user, AuditActionType.SEC_FILING_EXTRACTION, target.getName(), modelName,
                "Filing accession " + filing.accessionNumber() + " (" + filing.sourceType() + ", " + filing.filingDate() + ")",
                startedAtMs, success, outputSummary, errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeGeneric(User user, AuditActionType type, String targetEntity, String modelName, String inputReference,
                              long startedAtMs, boolean success, String outputSummary, String errorMessage) {
        AuditLog entry = new AuditLog(user, type, targetEntity, modelName);
        entry.setInputReference(inputReference);
        entry.setOutputSummary(outputSummary);
        entry.setLatencyMs(System.currentTimeMillis() - startedAtMs);
        entry.setSuccess(success);
        entry.setErrorMessage(errorMessage == null ? null
                : (errorMessage.length() > 2000 ? errorMessage.substring(0, 2000) : errorMessage));
        auditLogRepository.save(entry);
    }
}
