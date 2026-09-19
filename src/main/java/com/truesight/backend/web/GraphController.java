package com.truesight.backend.web;

import com.truesight.backend.common.NotFoundException;
import com.truesight.backend.common.ValidationException;
import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.PortfolioIngestionService;
import com.truesight.backend.repository.CompanyRepository;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.GraphResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Epic 4: the network graph, scoped to a portfolio the caller owns (404 otherwise). */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/graph")
@Tag(name = "Graph", description = "Supply-chain network for a portfolio (Epic 4).")
public class GraphController {

    private final PortfolioService portfolioService;
    private final GraphAssemblyService graphAssemblyService;
    private final PortfolioIngestionService ingestionService;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public GraphController(PortfolioService portfolioService, GraphAssemblyService graphAssemblyService,
                           PortfolioIngestionService ingestionService, CompanyRepository companyRepository,
                           UserRepository userRepository, CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.graphAssemblyService = graphAssemblyService;
        this.ingestionService = ingestionService;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Get the portfolio's supply-chain graph",
            description = "Nodes carry tier and dependant-holding count (the node-size input); edges carry criticality, "
                    + "confidence, review status and risk. includeRejected=true reveals user-rejected edges (AC 5.4 toggle).")
    public GraphResponse graph(@PathVariable Long portfolioId,
                               @RequestParam(name = "includeRejected", defaultValue = "false") boolean includeRejected) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return graphAssemblyService.buildResponse(portfolioId, currentUser.id(), includeRejected);
    }

    @PostMapping("/expand/{companyId}")
    @Operation(summary = "Expand a supplier to reveal its own suppliers (tier 2) — AC 4.8",
            description = "Analyses the supplier's own primary SEC filing. Cached by accession number, so a repeat is instant. "
                    + "The company must be in this portfolio's graph; expansion is capped at tier 2 by the graph walk.")
    public PortfolioIngestionService.ExpandOutcome expand(@PathVariable Long portfolioId, @PathVariable Long companyId) {
        Portfolio portfolio = portfolioService.getOwned(portfolioId, currentUser.id());
        boolean inGraph = graphAssemblyService.assemble(portfolioId, currentUser.id(), true).nodesByCompanyId().containsKey(companyId);
        if (!inGraph) {
            throw new NotFoundException("Company not found in this portfolio's graph");
        }
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Company not found"));
        if (company.getCik() == null) {
            throw new ValidationException(company.getName() + " has no SEC filings, so its suppliers cannot be extracted.");
        }
        User user = userRepository.getReferenceById(currentUser.id());
        return ingestionService.expandSupplier(user, portfolio, company);
    }
}
