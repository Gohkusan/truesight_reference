package com.truesight.backend.web;

import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.GraphQuestionService;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.GraphAnswer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AC 9.4: plain-language questions about the user's own graph, with verified citations. */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/ask")
@Tag(name = "Ask", description = "Ask about this portfolio's graph; answers cite verified nodes and excerpts (AC 9.4).")
public class GraphQuestionController {

    private final PortfolioService portfolioService;
    private final GraphQuestionService graphQuestionService;
    private final CurrentUser currentUser;

    public GraphQuestionController(PortfolioService portfolioService, GraphQuestionService graphQuestionService,
                                   CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.graphQuestionService = graphQuestionService;
        this.currentUser = currentUser;
    }

    public record QuestionRequest(@NotBlank @Size(max = 500) String question) {
    }

    @PostMapping
    @Operation(summary = "Ask a question about this portfolio's supply-chain graph",
            description = "The model sees only this user's graph and declines out-of-scope questions. Cited companies and "
                    + "excerpts are verified against the graph; anything unverifiable is listed separately.")
    public GraphAnswer ask(@PathVariable Long portfolioId, @Valid @RequestBody QuestionRequest request) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return graphQuestionService.ask(portfolioId, currentUser.id(), request.question());
    }
}
