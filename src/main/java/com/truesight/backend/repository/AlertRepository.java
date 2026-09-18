package com.truesight.backend.repository;

import com.truesight.backend.domain.Alert;
import com.truesight.backend.domain.AlertStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByIdAndPortfolioId(Long id, Long portfolioId);

    List<Alert> findByPortfolioIdOrderByEventAtDesc(Long portfolioId);

    List<Alert> findByPortfolioIdAndStatusOrderByEventAtDesc(Long portfolioId, AlertStatus status);

    long countByPortfolioIdAndCreatedAtAfter(Long portfolioId, Instant since);

    /** AC 7.1: alerts tied to a removed holding's company are archived automatically. */
    List<Alert> findByPortfolioIdAndSourceCompanyId(Long portfolioId, Long companyId);
}
