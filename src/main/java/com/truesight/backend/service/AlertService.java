package com.truesight.backend.service;

import com.truesight.backend.common.NotFoundException;
import com.truesight.backend.domain.Alert;
import com.truesight.backend.domain.AlertSeverity;
import com.truesight.backend.domain.AlertStatus;
import com.truesight.backend.domain.DismissReason;
import com.truesight.backend.domain.UserSettings;
import com.truesight.backend.repository.AlertRepository;
import com.truesight.backend.repository.UserSettingsRepository;
import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.risk.GraphAssemblyService.AssembledGraph;
import com.truesight.backend.risk.GraphAssemblyService.NodeModel;
import com.truesight.backend.web.dto.AlertResponse;
import com.truesight.backend.web.dto.RiskResponses.AffectedHolding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/update side of Epic 7. Alert CREATION lives in NewsIngestionService (the
 * pipeline that fetches, dedupes and thresholds articles); this class answers "which
 * alerts should this user see, and what do they mean for their holdings".
 *
 * <p>AC 7.2's "affected holdings are derived from the graph" is done here at read
 * time, not stored on the alert: a supplier event maps to every holding connected to
 * that supplier NOW, which can be more than when the alert was created if a later
 * analysis discovered another connection.
 */
@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final GraphAssemblyService graphAssemblyService;
    private final UserSettingsRepository userSettingsRepository;

    public AlertService(AlertRepository alertRepository, GraphAssemblyService graphAssemblyService,
                        UserSettingsRepository userSettingsRepository) {
        this.alertRepository = alertRepository;
        this.graphAssemblyService = graphAssemblyService;
        this.userSettingsRepository = userSettingsRepository;
    }

    /**
     * AC 7.3/7.4: dismissed and archived alerts hidden by default; filter by
     * severity, holding, and date; the user's own minimum-severity setting applies
     * unless the caller asks for everything.
     */
    @Transactional(readOnly = true)
    public List<AlertResponse> list(Long portfolioId, Long userId, AlertStatus status, AlertSeverity minSeverity,
                                    Long holdingId, Instant since, boolean includeDismissed) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolioId, userId, false);
        AlertSeverity floor = minSeverity != null ? minSeverity
                : userSettingsRepository.findByUserId(userId).map(UserSettings::getMinimumAlertSeverity).orElse(AlertSeverity.LOW);

        List<AlertResponse> out = new ArrayList<>();
        for (Alert a : alertRepository.findByPortfolioIdOrderByEventAtDesc(portfolioId)) {
            if (status != null && a.getStatus() != status) {
                continue;
            }
            if (status == null && !includeDismissed
                    && (a.getStatus() == AlertStatus.DISMISSED || a.getStatus() == AlertStatus.ARCHIVED)) {
                continue;
            }
            if (rank(a.getSeverity()) < rank(floor)) {
                continue;
            }
            if (since != null && a.getEventAt().isBefore(since)) {
                continue;
            }
            AlertResponse r = toResponse(a, graph);
            if (holdingId != null && r.affectedHoldings().stream().noneMatch(h -> h.holdingId().equals(holdingId))) {
                continue;
            }
            out.add(r);
        }
        // Rank before render: severity first, then recency.
        out.sort(Comparator.comparingInt((AlertResponse r) -> rank(r.severity())).reversed()
                .thenComparing(AlertResponse::eventAt, Comparator.reverseOrder()));
        return out;
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> recent(Long portfolioId, Long userId, int limit) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolioId, userId, false);
        return alertRepository.findByPortfolioIdOrderByEventAtDesc(portfolioId).stream()
                .filter(a -> a.getStatus() == AlertStatus.NEW || a.getStatus() == AlertStatus.REVIEWED)
                .limit(limit)
                .map(a -> toResponse(a, graph))
                .toList();
    }

    @Transactional
    public AlertResponse markReviewed(Long alertId, Long portfolioId, Long userId) {
        Alert a = owned(alertId, portfolioId);
        a.setStatus(AlertStatus.REVIEWED);
        a.setDismissReason(null);
        return toResponse(a, graphAssemblyService.assemble(portfolioId, userId, false));
    }

    /** AC 7.3: dismiss with a reason; reversible via {@link #reopen}. */
    @Transactional
    public AlertResponse dismiss(Long alertId, Long portfolioId, Long userId, DismissReason reason) {
        Alert a = owned(alertId, portfolioId);
        a.setStatus(AlertStatus.DISMISSED);
        a.setDismissReason(reason);
        return toResponse(a, graphAssemblyService.assemble(portfolioId, userId, false));
    }

    /** AC 10.4: undo a dismissal (or a review) — back to NEW. */
    @Transactional
    public AlertResponse reopen(Long alertId, Long portfolioId, Long userId) {
        Alert a = owned(alertId, portfolioId);
        a.setStatus(AlertStatus.NEW);
        a.setDismissReason(null);
        return toResponse(a, graphAssemblyService.assemble(portfolioId, userId, false));
    }

    /** AC 2.4 / 7.1: alerts tied to a removed holding are archived, not deleted. */
    @Transactional
    public void archiveForCompany(Long portfolioId, Long companyId) {
        for (Alert a : alertRepository.findByPortfolioIdAndSourceCompanyId(portfolioId, companyId)) {
            a.setStatus(AlertStatus.ARCHIVED);
        }
    }

    public AlertResponse toResponse(Alert a, AssembledGraph graph) {
        NodeModel node = graph.nodesByCompanyId().get(a.getSourceCompany().getId());
        List<AffectedHolding> affected = new ArrayList<>();
        boolean sourceIsHolding = node != null && node.isHolding();
        if (node != null) {
            if (node.isHolding()) {
                affected.add(new AffectedHolding(node.holding.getId(), node.company.getTicker(),
                        node.company.getName(), node.holding.getWeightPercent()));
            }
            for (Long hid : node.dependantHoldingCompanyIds) {
                NodeModel h = graph.nodesByCompanyId().get(hid);
                if (h != null && h.isHolding()) {
                    affected.add(new AffectedHolding(h.holding.getId(), h.company.getTicker(),
                            h.company.getName(), h.holding.getWeightPercent()));
                }
            }
        }
        return AlertResponse.from(a, sourceIsHolding, affected);
    }

    private Alert owned(Long alertId, Long portfolioId) {
        return alertRepository.findByIdAndPortfolioId(alertId, portfolioId)
                .orElseThrow(() -> new NotFoundException("Alert not found"));
    }

    private static int rank(AlertSeverity s) {
        return switch (s) {
            case CRITICAL -> 4;
            case ELEVATED -> 3;
            case MODERATE -> 2;
            case LOW -> 1;
        };
    }

    public static int severityRank(AlertSeverity s) {
        return rank(s);
    }
}
