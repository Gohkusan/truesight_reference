package com.truesight.backend.web;

import com.truesight.backend.domain.AuditActionType;
import com.truesight.backend.ingestion.llm.AuditLogWriter;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.risk.SimulationService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.SimulationDtos.CompareRequest;
import com.truesight.backend.web.dto.SimulationDtos.CompareResult;
import com.truesight.backend.web.dto.SimulationDtos.ScenarioRequest;
import com.truesight.backend.web.dto.SimulationDtos.ScenarioResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Epic 12. Deterministic graph propagation; nothing persisted; every run audited (AC 9.3). */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/simulations")
@Tag(name = "Simulation", description = "Scenario propagation downstream from an epicentre (Epic 12).")
public class SimulationController {

    private final PortfolioService portfolioService;
    private final SimulationService simulationService;
    private final AuditLogWriter auditLogWriter;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public SimulationController(PortfolioService portfolioService, SimulationService simulationService,
                                AuditLogWriter auditLogWriter, UserRepository userRepository, CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.simulationService = simulationService;
        this.auditLogWriter = auditLogWriter;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Run one scenario (AC 12.1/12.2)")
    public ScenarioResult run(@PathVariable Long portfolioId, @Valid @RequestBody ScenarioRequest request) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        long started = System.currentTimeMillis();
        ScenarioResult result = simulationService.run(portfolioId, currentUser.id(), request);
        auditLogWriter.writeGeneric(userRepository.getReferenceById(currentUser.id()), AuditActionType.SIMULATION_PROPAGATION,
                String.join(", ", result.epicentreNames()), "deterministic-graph-propagation",
                "severity=" + request.severityPercent() + "% duration=" + request.durationDays() + "d",
                started, true, result.affectedHoldings().size() + " holding(s) affected, "
                        + result.totalWeightExposed() + "% weight exposed", null);
        return result;
    }

    @PostMapping("/compare")
    @Operation(summary = "Run two scenarios and compare exposure side by side (AC 12.3)")
    public CompareResult compare(@PathVariable Long portfolioId, @Valid @RequestBody CompareRequest request) {
        ScenarioResult a = run(portfolioId, request.a());
        ScenarioResult b = run(portfolioId, request.b());
        BigDecimal diff = a.totalWeightExposed().subtract(b.totalWeightExposed());
        String more = diff.signum() > 0 ? "A" : diff.signum() < 0 ? "B" : "EQUAL";
        return new CompareResult(a, b, diff.abs(), more);
    }
}
