package com.truesight.backend.web;

import com.truesight.backend.domain.AlertSeverity;
import com.truesight.backend.domain.AlertStatus;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.AlertService;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.AlertResponse;
import com.truesight.backend.web.dto.DismissRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Epic 7 read/update endpoints. Alert creation is the news pipeline's job (NewsIngestionService). */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/alerts")
@Tag(name = "Alerts", description = "Early-warning alerts: list, filter, review, dismiss, reopen (Epic 7).")
public class AlertController {

    private final PortfolioService portfolioService;
    private final AlertService alertService;
    private final CurrentUser currentUser;

    public AlertController(PortfolioService portfolioService, AlertService alertService, CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.alertService = alertService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List alerts", description = "AC 7.4: filter by status, minimum severity, holding, and date. "
            + "Dismissed/archived hidden unless includeDismissed=true or status is given explicitly.")
    public List<AlertResponse> list(@PathVariable Long portfolioId,
                                    @RequestParam(required = false) AlertStatus status,
                                    @RequestParam(required = false) AlertSeverity minSeverity,
                                    @RequestParam(required = false) Long holdingId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
                                    @RequestParam(defaultValue = "false") boolean includeDismissed) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return alertService.list(portfolioId, currentUser.id(), status, minSeverity, holdingId, since, includeDismissed);
    }

    @PutMapping("/{alertId}/review")
    @Operation(summary = "Mark an alert as reviewed (AC 7.3)")
    public AlertResponse review(@PathVariable Long portfolioId, @PathVariable Long alertId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return alertService.markReviewed(alertId, portfolioId, currentUser.id());
    }

    @PutMapping("/{alertId}/dismiss")
    @Operation(summary = "Dismiss an alert with a reason (AC 7.3); reversible via reopen (AC 10.4)")
    public AlertResponse dismiss(@PathVariable Long portfolioId, @PathVariable Long alertId,
                                 @Valid @RequestBody DismissRequest request) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return alertService.dismiss(alertId, portfolioId, currentUser.id(), request.reason());
    }

    @PutMapping("/{alertId}/reopen")
    @Operation(summary = "Undo a dismissal or review: back to NEW (AC 10.4)")
    public AlertResponse reopen(@PathVariable Long portfolioId, @PathVariable Long alertId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return alertService.reopen(alertId, portfolioId, currentUser.id());
    }
}
