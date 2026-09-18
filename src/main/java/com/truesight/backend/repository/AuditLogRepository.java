package com.truesight.backend.repository;

import com.truesight.backend.domain.AuditActionType;
import com.truesight.backend.domain.AuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<AuditLog> findByUserIdAndActionTypeOrderByCreatedAtDesc(Long userId, AuditActionType actionType);

    List<AuditLog> findByUserIdAndTargetEntityOrderByCreatedAtDesc(Long userId, String targetEntity);
}
