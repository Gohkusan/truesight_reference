package com.truesight.backend.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.CoverageStatus;
import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Evidence;
import com.truesight.backend.domain.Holding;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipReview;
import com.truesight.backend.domain.RelationshipType;
import com.truesight.backend.domain.ReviewStatus;
import com.truesight.backend.domain.RiskSeverity;
import com.truesight.backend.domain.SourceType;
import com.truesight.backend.domain.User;
import com.truesight.backend.repository.CompanyRepository;
import com.truesight.backend.repository.HoldingRepository;
import com.truesight.backend.repository.PortfolioRepository;
import com.truesight.backend.repository.RelationshipRepository;
import com.truesight.backend.repository.RelationshipReviewRepository;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.web.dto.GraphResponse;
import com.truesight.backend.web.dto.RiskResponses.RiskItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a small real graph in H2 — two holdings sharing one single-source supplier,
 * plus a diversified supplier feeding one holding — and checks the numbers every
 * screen depends on agree with each other:
 * <ul>
 *   <li>the shared supplier's dependant count is 2 in the graph AND in the shared-
 *       supplier table (AC 4.4/4.5 "must never disagree");</li>
 *   <li>the single-source edge scores higher than the diversified one, and carries a
 *       "single-source" factor (AC 6.1);</li>
 *   <li>a holding with no relationships is UNKNOWN, not LOW (AC 6.1);</li>
 *   <li>rejecting the shared edge for this user removes it from the graph and from the
 *       count, and the rescore records why (AC 5.4, 8.2).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RiskScoringIntegrationTest {

    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolios;
    @Autowired CompanyRepository companies;
    @Autowired HoldingRepository holdings;
    @Autowired RelationshipRepository relationships;
    @Autowired RelationshipReviewRepository reviews;
    @Autowired GraphAssemblyService graph;
    @Autowired RiskScoringService scoring;
    @Autowired RiskQueryService queries;

    @Test
    void sharedSingleSourceSupplierIsCountedOnceAndScoredHighest() {
        User user = users.save(new User("risk-test@example.com", "hash"));
        Portfolio p = portfolios.save(new Portfolio(user, "Test book"));

        Company nvda = company("NVIDIA Corp", "NVDA", "0001045810");
        Company amd = company("Advanced Micro Devices", "AMD", "0000002488");
        Company tsmc = company("Taiwan Semiconductor Manufacturing", "TSM", "0001046179");
        Company foxconn = company("Hon Hai Precision", null, null);
        Company lonely = company("Lonely Holdings", "LNLY", "0009999999");

        Holding hNvda = holding(p, nvda, "40");
        Holding hAmd = holding(p, amd, "30");
        Holding hLonely = holding(p, lonely, "30");

        // TSMC single-sources both NVDA and AMD; Foxconn diversified into NVDA only.
        edge(tsmc, nvda, Criticality.SINGLE_SOURCE, "0001045810-24-000001");
        edge(tsmc, amd, Criticality.SINGLE_SOURCE, "0000002488-24-000001");
        edge(foxconn, nvda, Criticality.DIVERSIFIED, "0001045810-24-000001");

        scoring.rescorePortfolio(p.getId(), user.getId(), "Initial analysis");

        GraphResponse g = graph.toResponse(graph.assemble(p.getId(), user.getId(), false));
        GraphResponse.GraphNode tsmcNode = g.nodes().stream().filter(n -> n.id().equals(tsmc.getId())).findFirst().orElseThrow();
        assertThat(tsmcNode.dependantHoldingCount()).isEqualTo(2);
        assertThat(tsmcNode.tier()).isEqualTo(1);
        assertThat(g.sharedSuppliers()).hasSize(1);
        assertThat(g.sharedSuppliers().get(0).holdingCount()).isEqualTo(tsmcNode.dependantHoldingCount());
        assertThat(g.sharedSuppliers().get(0).combinedWeightPercent()).isEqualByComparingTo("70");

        List<RiskItem> risks = queries.list(p.getId(), user.getId(), null, null);
        RiskItem tsmcToNvda = risks.stream().filter(r -> "RELATIONSHIP".equals(r.kind()) && r.title().startsWith("Taiwan") && r.title().endsWith("NVIDIA Corp")).findFirst().orElseThrow();
        RiskItem foxToNvda = risks.stream().filter(r -> "RELATIONSHIP".equals(r.kind()) && r.title().startsWith("Hon Hai")).findFirst().orElseThrow();
        assertThat(tsmcToNvda.score()).isGreaterThan(foxToNvda.score());
        assertThat(tsmcToNvda.factors()).anyMatch(f -> f.startsWith("Single-source"));
        assertThat(tsmcToNvda.factors()).anyMatch(f -> f.startsWith("Shared supplier: 2"));
        assertThat(tsmcToNvda.affectedHoldings()).hasSize(2);
        assertThat(tsmcToNvda.totalWeightExposed()).isEqualByComparingTo("70");
        assertThat(tsmcToNvda.trend()).isEqualTo("NEW");

        RiskItem lonelyItem = risks.stream().filter(r -> "HOLDING".equals(r.kind()) && r.holdingId().equals(hLonely.getId())).findFirst().orElseThrow();
        assertThat(lonelyItem.severity()).isEqualTo(RiskSeverity.UNKNOWN);
        assertThat(lonelyItem.score()).isNull();
        assertThat(risks.get(risks.size() - 1).severity()).isEqualTo(RiskSeverity.UNKNOWN); // Unknown sorts last

        var summary = queries.summary(p.getId(), user.getId());
        assertThat(summary.scoredHoldings()).isEqualTo(2);
        assertThat(summary.unknownHoldings()).isEqualTo(1);
        assertThat(summary.compositeScore()).isNotNull();

        // Reject TSMC -> AMD for this user: TSMC now feeds only NVDA, so it is no longer shared.
        Relationship tsmcAmd = relationships.findByFromCompanyIdAndToCompanyIdAndRelationshipType(tsmc.getId(), amd.getId(), RelationshipType.SUPPLIER).orElseThrow();
        RelationshipReview review = new RelationshipReview(user, tsmcAmd);
        review.setStatus(ReviewStatus.REJECTED);
        reviews.save(review);
        scoring.rescorePortfolio(p.getId(), user.getId(), "Relationship rejected");

        GraphResponse g2 = graph.toResponse(graph.assemble(p.getId(), user.getId(), false));
        assertThat(g2.sharedSuppliers()).isEmpty();
        assertThat(g2.rejectedEdgesHidden()).isEqualTo(1);
        RiskItem tsmcToNvdaAfter = queries.list(p.getId(), user.getId(), null, null).stream()
                .filter(r -> r.relationshipId() != null && r.relationshipId().equals(tsmcToNvda.relationshipId())).findFirst().orElseThrow();
        assertThat(tsmcToNvdaAfter.score()).isLessThan(tsmcToNvda.score());
        assertThat(tsmcToNvdaAfter.trend()).isEqualTo("FALLING");
        assertThat(tsmcToNvdaAfter.changeReason()).contains("removed: Shared supplier").contains("Relationship rejected");
        assertThat(hNvda.getId()).isNotNull();
        assertThat(hAmd.getId()).isNotNull();
    }

    private Company company(String name, String ticker, String cik) {
        Company c = new Company(name, name.toUpperCase());
        c.setTicker(ticker);
        c.setCik(cik);
        return companies.save(c);
    }

    private Holding holding(Portfolio p, Company c, String weight) {
        Holding h = new Holding(p, c);
        h.setWeightPercent(new BigDecimal(weight));
        h.setCoverageStatus(CoverageStatus.COVERED);
        return holdings.save(h);
    }

    private void edge(Company from, Company to, Criticality crit, String accession) {
        Relationship r = new Relationship(from, to, RelationshipType.SUPPLIER);
        r.setCriticality(crit);
        Evidence e = new Evidence(r, SourceType.FORM_10K, "We rely on " + from.getName() + ".");
        e.setAccessionNumber(accession);
        e.setSourceDate(LocalDate.now().minusMonths(2));
        r.getEvidence().add(e);
        relationships.save(r);
    }
}
