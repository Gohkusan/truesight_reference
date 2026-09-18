package com.truesight.backend.web;

import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.CreatePortfolioRequest;
import com.truesight.backend.web.dto.PortfolioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC 2.7: named, switchable portfolios. Every method here loads through
 * PortfolioService#getOwned (id+ownerId), so a request for another account's portfolio
 * id returns 404 uniformly — see PortfolioService's Javadoc for the reasoning.
 */
@RestController
@RequestMapping("/api/portfolios")
@Tag(name = "Portfolios", description = "Named books of holdings, scoped to the authenticated account (AC 1.3, AC 2.7).")
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final CurrentUser currentUser;

    public PortfolioController(PortfolioService portfolioService, CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List my portfolios")
    public List<PortfolioResponse> list() {
        return portfolioService.listForUser(currentUser.id()).stream()
                .map(PortfolioResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one portfolio", description = "404 if it does not exist or belongs to another account (AC 1.3).")
    public PortfolioResponse get(@PathVariable Long id) {
        return PortfolioResponse.from(portfolioService.getOwned(id, currentUser.id()));
    }

    @PostMapping
    @Operation(summary = "Create a portfolio")
    public ResponseEntity<PortfolioResponse> create(@Valid @RequestBody CreatePortfolioRequest request) {
        Portfolio created = portfolioService.create(currentUser.id(), request.name());
        return ResponseEntity.ok(PortfolioResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a portfolio")
    public PortfolioResponse rename(@PathVariable Long id, @Valid @RequestBody CreatePortfolioRequest request) {
        return PortfolioResponse.from(portfolioService.rename(id, currentUser.id(), request.name()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a portfolio", description = "AC 2.7: cascades to this portfolio's holdings and alerts only. Frontend must confirm before calling this.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        portfolioService.delete(id, currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
