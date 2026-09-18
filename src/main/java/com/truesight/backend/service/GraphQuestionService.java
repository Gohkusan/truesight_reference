package com.truesight.backend.service;

import com.truesight.backend.domain.AuditActionType;
import com.truesight.backend.domain.Evidence;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.llm.AuditLogWriter;
import com.truesight.backend.ingestion.llm.ExcerptVerificationService;
import com.truesight.backend.ingestion.llm.GeminiClient;
import com.truesight.backend.ingestion.llm.LlmHealthTracker;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.risk.GraphAssemblyService.AssembledGraph;
import com.truesight.backend.risk.GraphAssemblyService.EdgeModel;
import com.truesight.backend.risk.GraphAssemblyService.NodeModel;
import com.truesight.backend.web.dto.GraphAnswer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AC 9.4: "answers must reference nodes or sources present in the user's portfolio;
 * the assistant declines questions outside that scope."
 *
 * <p>Scope is enforced twice. First, by construction: the model is given ONLY a text
 * rendering of this user's graph (nodes, counted edges, and each edge's excerpts) and
 * told to answer from it alone. Second, after the fact: every company the model cites
 * is checked against the graph's node names, and every quoted excerpt is checked
 * verbatim against the graph's Evidence rows with the same guard used at extraction
 * time. A citation that fails either check is moved to unverifiedCitations and shown
 * as such — the answer is never silently trusted just because it sounds specific.
 *
 * <p>Stateless (no conversation memory) and audited like every other LLM call.
 */
@Service
public class GraphQuestionService {

    private static final int MAX_CONTEXT_CHARS = 40_000;

    private final GraphAssemblyService graphAssemblyService;
    private final GeminiClient geminiClient;
    private final ExcerptVerificationService excerptVerificationService;
    private final AuditLogWriter auditLogWriter;
    private final LlmHealthTracker llmHealthTracker;
    private final UserRepository userRepository;

    public GraphQuestionService(GraphAssemblyService graphAssemblyService, GeminiClient geminiClient,
                                ExcerptVerificationService excerptVerificationService, AuditLogWriter auditLogWriter,
                                LlmHealthTracker llmHealthTracker, UserRepository userRepository) {
        this.graphAssemblyService = graphAssemblyService;
        this.geminiClient = geminiClient;
        this.excerptVerificationService = excerptVerificationService;
        this.auditLogWriter = auditLogWriter;
        this.llmHealthTracker = llmHealthTracker;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public GraphAnswer ask(Long portfolioId, Long userId, String question) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolioId, userId, false);
        if (graph.nodesByCompanyId().isEmpty()) {
            return new GraphAnswer(false, "This portfolio has no analysed holdings yet, so there is nothing to answer from.",
                    List.of(), List.of(), List.of());
        }
        String context = render(graph);
        Set<String> knownNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        StringBuilder allExcerpts = new StringBuilder();
        for (NodeModel n : graph.nodesByCompanyId().values()) {
            knownNames.add(n.company.getName());
            if (n.company.getTicker() != null) {
                knownNames.add(n.company.getTicker());
            }
        }
        for (EdgeModel e : graph.edges()) {
            for (Evidence ev : e.relationship.getEvidence()) {
                allExcerpts.append(ev.getExcerpt()).append('\n');
            }
        }

        User user = userRepository.getReferenceById(userId);
        long started = System.currentTimeMillis();
        GraphAnswer raw;
        try {
            raw = geminiClient.answerQuestion(context, question);
            llmHealthTracker.recordSuccess();
        } catch (Exception e) {
            llmHealthTracker.recordFailure(e);
            auditLogWriter.writeGeneric(user, AuditActionType.GRAPH_QUESTION_ANSWERING, "PORTFOLIO_" + portfolioId,
                    geminiClient.currentModelName(), question, started, false, null, e.getMessage());
            throw new RuntimeException("Question could not be answered: the AI service is unavailable", e);
        }

        List<String> verifiedCompanies = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        for (String c : raw.citedCompanies()) {
            if (knownNames.contains(c)) {
                verifiedCompanies.add(c);
            } else {
                unverified.add(c);
            }
        }
        List<String> verifiedExcerpts = new ArrayList<>();
        String excerptCorpus = allExcerpts.toString();
        for (String q : raw.citedExcerpts()) {
            if (excerptVerificationService.isVerbatim(q, excerptCorpus)) {
                verifiedExcerpts.add(q);
            } else {
                unverified.add("excerpt: " + (q.length() > 80 ? q.substring(0, 80) + "…" : q));
            }
        }

        auditLogWriter.writeGeneric(user, AuditActionType.GRAPH_QUESTION_ANSWERING, "PORTFOLIO_" + portfolioId,
                geminiClient.currentModelName(), question, started, true,
                (raw.answered() ? "answered" : "declined") + "; " + verifiedCompanies.size() + " verified citation(s), "
                        + unverified.size() + " unverified", null);

        return new GraphAnswer(raw.answered(), raw.answer(), verifiedCompanies, verifiedExcerpts, unverified);
    }

    /** A compact, deterministic text rendering of the graph: what the model is allowed to know. */
    private static String render(AssembledGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("HOLDINGS:\n");
        for (NodeModel n : graph.nodesByCompanyId().values()) {
            if (n.isHolding()) {
                sb.append("- ").append(n.company.getName()).append(" (").append(n.company.getTicker()).append("), weight ")
                  .append(n.holding.getWeightPercent()).append("%, coverage ").append(n.holding.getCoverageStatus()).append('\n');
            }
        }
        sb.append("\nSUPPLIERS / CUSTOMERS:\n");
        for (NodeModel n : graph.nodesByCompanyId().values()) {
            if (!n.isHolding()) {
                sb.append("- ").append(n.company.getName()).append(n.tier < 0 ? " [customer]" : " [tier " + n.tier + " supplier]")
                  .append(", depended on by ").append(n.dependantHoldingCompanyIds.size()).append(" holding(s)")
                  .append(n.company.getCountry() == null ? "" : ", country " + n.company.getCountry())
                  .append(n.company.getSector() == null ? "" : ", sector " + n.company.getSector()).append('\n');
            }
        }
        sb.append("\nRELATIONSHIPS (supplier -> buyer):\n");
        for (EdgeModel e : graph.edges()) {
            Relationship r = e.relationship;
            sb.append("- ").append(r.getFromCompany().getName()).append(" -> ").append(r.getToCompany().getName())
              .append(": ").append(r.getCriticality().name().toLowerCase(Locale.ROOT))
              .append(r.getDependencyPercent() == null ? "" : ", " + r.getDependencyPercent() + "% dependency")
              .append(", confidence ").append(r.getConfidenceScore())
              .append(e.countsTowardRisk() ? "" : " [excluded: rejected or no longer disclosed]").append('\n');
            for (Evidence ev : r.getEvidence()) {
                sb.append("    excerpt (").append(ev.getSourceType()).append(' ').append(ev.getSourceDate()).append("): \"")
                  .append(ev.getExcerpt()).append("\"\n");
            }
            if (sb.length() > MAX_CONTEXT_CHARS) {
                sb.append("... [graph truncated for length]\n");
                break;
            }
        }
        return sb.toString();
    }
}
