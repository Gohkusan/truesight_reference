package com.truesight.backend.web;

import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.GraphResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final CurrentUser currentUser;

    public GraphController(PortfolioService portfolioService, GraphAssemblyService graphAssemblyService, CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.graphAssemblyService = graphAssemblyService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Get the portfolio's supply-chain graph",
            description = "Nodes carry tier and dependant-holding count (the node-size input); edges carry criticality, "
                    + "confidence, review status and risk. includeRejected=true reveals user-rejected edges (AC 5.4 toggle).")
    public GraphResponse graph(@PathVariable Long portfolioId,
                               @RequestParam(name = "includeRejected", defaultValue = "false") boolean includeRejected) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        var assembled = graphAssemblyService.assemble(portfolioId, currentUser.id(), includeRejected);
        return graphAssemblyService.toResponse(assembled);
    }
}
