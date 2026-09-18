package com.truesight.backend.ingestion.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipType;
import com.truesight.backend.domain.SourceType;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.CompanyResolutionService;
import com.truesight.backend.ingestion.sec.FetchedFiling;
import com.truesight.backend.repository.AuditLogRepository;
import com.truesight.backend.repository.RelationshipRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * This is the test that proves the verbatim excerpt guard is actually LOAD-BEARING —
 * not just implemented, but genuinely the thing standing between a fabricated LLM
 * excerpt and a Relationship row landing in the database. GeminiClient is mocked to
 * return a mix of a real (verbatim) excerpt and a fabricated one; the assertions
 * confirm only the relationship backed by the real excerpt is ever persisted.
 */
@ExtendWith(MockitoExtension.class)
class GeminiExtractionServiceTest {

    @Mock
    private GeminiClient geminiClient;
    @Mock
    private CompanyResolutionService companyResolutionService;
    @Mock
    private RelationshipRepository relationshipRepository;
    @Mock
    private AuditLogRepository auditLogRepository;

    private GeminiExtractionService service;

    // Real instance, not mocked — this test is specifically about proving the REAL
    // guard rejects a fabricated excerpt, so using the real implementation here is
    // the point, not an oversight.
    private final ExcerptVerificationService excerptVerificationService = new ExcerptVerificationService();

    private User user;
    private Company filerCompany;
    private FetchedFiling filing;

    @BeforeEach
    void setUp() {
        service = new GeminiExtractionService(
                geminiClient, excerptVerificationService, companyResolutionService,
                relationshipRepository, auditLogRepository);

        user = new User("analyst@example.com", "hash");
        filerCompany = new Company("NVIDIA Corporation", "NVIDIA");
        setId(filerCompany, 1L);

        String realFilingText = "Item 1A. Risk Factors. We depend on Taiwan Semiconductor Manufacturing "
                + "Company as our sole foundry partner for advanced-node chips, and any disruption "
                + "to that relationship could materially harm our operations.";
        filing = new FetchedFiling("0000320193-24-000123", SourceType.FORM_10K,
                LocalDate.of(2024, 3, 1), "https://sec.gov/some-filing", realFilingText);
    }

    @Test
    void onlyPersistsRelationshipsBackedByAGenuineVerbatimExcerpt() throws Exception {
        Company tsmc = new Company("Taiwan Semiconductor Manufacturing Company", "TAIWAN SEMICONDUCTOR MANUFACTURING");
        setId(tsmc, 2L);
        Company fabricatedCo = new Company("Totally Fictional Supplier Inc", "TOTALLY FICTIONAL SUPPLIER");
        setId(fabricatedCo, 3L);

        // One relationship backed by a REAL excerpt (present verbatim in filing.fullText),
        // one backed by a FABRICATED excerpt (plausible-sounding, but not actually in
        // the filing text at all).
        ExtractedRelationship realRelationship = new ExtractedRelationship(
                "Taiwan Semiconductor Manufacturing Company", "TSM", "SUPPLIER", "SINGLE_SOURCE", 100,
                "Sole foundry partner",
                List.of(new ExtractedRelationship.ExtractedExcerpt(
                        "We depend on Taiwan Semiconductor Manufacturing Company as our sole foundry partner "
                                + "for advanced-node chips")));

        ExtractedRelationship fabricatedRelationship = new ExtractedRelationship(
                "Totally Fictional Supplier Inc", null, "SUPPLIER", "SINGLE_SOURCE", 80,
                "Invented dependency",
                List.of(new ExtractedRelationship.ExtractedExcerpt(
                        "We rely exclusively on Totally Fictional Supplier Inc for all critical components")));

        ExtractionResult mockResult = new ExtractionResult("Semiconductors", "US",
                List.of(realRelationship, fabricatedRelationship));

        when(geminiClient.extractRelationships(anyString(), anyString())).thenReturn(mockResult);
        when(geminiClient.currentModelName()).thenReturn("gemini-2.5-flash");
        when(companyResolutionService.resolveByName("Taiwan Semiconductor Manufacturing Company")).thenReturn(tsmc);
        when(relationshipRepository.findByFromCompanyIdAndToCompanyIdAndRelationshipType(2L, 1L, RelationshipType.SUPPLIER))
                .thenReturn(Optional.empty());
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GeminiExtractionService.ExtractionOutcome outcome = service.extractAndPersist(user, filerCompany, filing);

        // Both were EXTRACTED by the mocked model, but only one PERSISTED.
        assertThat(outcome.relationshipsExtracted()).isEqualTo(2);
        assertThat(outcome.relationshipsPersisted()).isEqualTo(1);
        assertThat(outcome.excerptsRejected()).isEqualTo(1);

        // The fabricated supplier must NEVER have been resolved to a Company at all —
        // resolution (and therefore persistence) never happens for a relationship
        // whose every excerpt failed verification.
        verify(companyResolutionService, never()).resolveByName("Totally Fictional Supplier Inc");

        // Exactly one relationship was ever saved, and it's the real one.
        ArgumentCaptor<Relationship> savedCaptor = ArgumentCaptor.forClass(Relationship.class);
        verify(relationshipRepository, org.mockito.Mockito.times(1)).save(savedCaptor.capture());
        Relationship saved = savedCaptor.getValue();
        assertThat(saved.getFromCompany()).isSameAs(tsmc);
        assertThat(saved.getToCompany()).isSameAs(filerCompany);
        assertThat(saved.getEvidence()).hasSize(1);
        assertThat(saved.getEvidence().get(0).getExcerpt())
                .isEqualTo("We depend on Taiwan Semiconductor Manufacturing Company as our sole foundry partner "
                        + "for advanced-node chips");
    }

    @Test
    void aRelationshipWithNoVerifiedExcerptsAtAllIsCompletelyDiscarded() throws Exception {
        ExtractedRelationship allFabricated = new ExtractedRelationship(
                "Nonexistent Corp", null, "SUPPLIER", "SINGLE_SOURCE", 50, "made up",
                List.of(new ExtractedRelationship.ExtractedExcerpt("This sentence does not appear in the filing at all.")));

        when(geminiClient.extractRelationships(anyString(), anyString()))
                .thenReturn(new ExtractionResult(null, null, List.of(allFabricated)));
        when(geminiClient.currentModelName()).thenReturn("gemini-2.5-flash");

        GeminiExtractionService.ExtractionOutcome outcome = service.extractAndPersist(user, filerCompany, filing);

        assertThat(outcome.relationshipsExtracted()).isEqualTo(1);
        assertThat(outcome.relationshipsPersisted()).isZero();
        verify(relationshipRepository, never()).save(any());
        verify(companyResolutionService, never()).resolveByName(anyString());
    }

    @Test
    void retriesExactlyOnceOnFailureThenThrows() throws Exception {
        when(geminiClient.extractRelationships(anyString(), anyString()))
                .thenThrow(new java.io.IOException("simulated network failure"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.extractAndPersist(user, filerCompany, filing));

        // Exactly 2 calls to the client: the AC 5.1 policy is "retried once" — one
        // original attempt plus one retry, never more.
        verify(geminiClient, org.mockito.Mockito.times(2)).extractRelationships(anyString(), anyString());
    }

    /** Test-only reflection helper: Company's id is JPA-generated and has no public setter. */
    private static void setId(Company company, Long id) {
        try {
            var field = Company.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(company, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
