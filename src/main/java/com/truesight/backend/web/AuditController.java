package com.truesight.backend.web;

import com.truesight.backend.domain.AuditActionType;
import com.truesight.backend.domain.AuditLog;
import com.truesight.backend.repository.AuditLogRepository;
import com.truesight.backend.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC 9.3: "audit trail of every AI action... viewable in the app and exportable as
 * JSON or CSV." Scoped to the caller's own entries (AC 1.3 applies to audit logs too).
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit", description = "Every AI call: model, action, target, latency, outcome (AC 9.3).")
public class AuditController {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUser currentUser;

    public AuditController(AuditLogRepository auditLogRepository, CurrentUser currentUser) {
        this.auditLogRepository = auditLogRepository;
        this.currentUser = currentUser;
    }

    public record AuditEntry(Long id, AuditActionType actionType, String targetEntity, String modelName,
                             String inputReference, String outputSummary, Long latencyMs, boolean success,
                             String errorMessage, Instant createdAt) {
        static AuditEntry from(AuditLog a) {
            return new AuditEntry(a.getId(), a.getActionType(), a.getTargetEntity(), a.getModelName(),
                    a.getInputReference(), a.getOutputSummary(), a.getLatencyMs(), a.isSuccess(),
                    a.getErrorMessage(), a.getCreatedAt());
        }
    }

    @GetMapping
    @Operation(summary = "My audit trail, newest first; optional filters by action type and target entity")
    public List<AuditEntry> list(@RequestParam(required = false) AuditActionType type,
                                 @RequestParam(required = false) String entity) {
        return entries(type, entity).stream().map(AuditEntry::from).toList();
    }

    @GetMapping(value = "/export.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Export my audit trail as a JSON file")
    public ResponseEntity<List<AuditEntry>> exportJson() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"truesight-audit.json\"")
                .body(entries(null, null).stream().map(AuditEntry::from).toList());
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Export my audit trail as RFC 4180 CSV")
    public ResponseEntity<byte[]> exportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("id,createdAt,actionType,targetEntity,modelName,inputReference,outputSummary,latencyMs,success,errorMessage\r\n");
        for (AuditLog a : entries(null, null)) {
            sb.append(a.getId()).append(',')
              .append(csv(a.getCreatedAt().toString())).append(',')
              .append(a.getActionType()).append(',')
              .append(csv(a.getTargetEntity())).append(',')
              .append(csv(a.getModelName())).append(',')
              .append(csv(a.getInputReference())).append(',')
              .append(csv(a.getOutputSummary())).append(',')
              .append(a.getLatencyMs() == null ? "" : a.getLatencyMs()).append(',')
              .append(a.isSuccess()).append(',')
              .append(csv(a.getErrorMessage())).append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"truesight-audit.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<AuditLog> entries(AuditActionType type, String entity) {
        Long userId = currentUser.id();
        if (type != null) {
            return auditLogRepository.findByUserIdAndActionTypeOrderByCreatedAtDesc(userId, type);
        }
        if (entity != null && !entity.isBlank()) {
            return auditLogRepository.findByUserIdAndTargetEntityOrderByCreatedAtDesc(userId, entity);
        }
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** RFC 4180: quote when the value contains a comma, quote, or newline; double embedded quotes. */
    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
