package com.truesight.backend.ingestion;

import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.CoverageStatus;
import com.truesight.backend.domain.Holding;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.ProcessedInput;
import com.truesight.backend.domain.ProcessedInputType;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.csv.CsvParseResult;
import com.truesight.backend.ingestion.csv.ParsedCsvRow;
import com.truesight.backend.ingestion.csv.PortfolioCsvParser;
import com.truesight.backend.ingestion.llm.GeminiExtractionService;
import com.truesight.backend.ingestion.sec.FetchedFiling;
import com.truesight.backend.ingestion.sec.SecEdgarClient;
import com.truesight.backend.ingestion.sec.SecFilingSummary;
import com.truesight.backend.ingestion.sec.SecTickerIndexService;
import com.truesight.backend.repository.HoldingRepository;
import com.truesight.backend.repository.ProcessedInputRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The orchestrator that ties the whole ingestion pipeline together: CSV preview,
 * confirm-and-apply, then per-holding SEC fetch → Gemini extraction, respecting cancel
 * requests between holdings (AC 2.5) and skipping already-processed filings (AC 8.1).
 *
 * <p>Deliberately sequential per holding within this reference implementation, NOT the
 * old prototype doc's CompletableFuture-based parallel worker pool. That is a real,
 * stated simplification: SecRateLimiter already caps SEC concurrency and paces
 * requests, so parallelising the OUTER per-holding loop would mostly just mean N
 * threads blocked waiting on the same rate limiter rather than genuine throughput —
 * the gain is real but modest, and sequential processing is dramatically easier to
 * reason about (progress reporting, cancel-between-holdings, and per-holding error
 * isolation all fall out for free from "just loop and check a flag each iteration").
 * A team with time to invest in throughput would parallelise this with a bounded
 * executor exactly as the old prototype doc describes; documented here as a deliberate
 * choice for THIS version, not an oversight.
 */
@Service
public class PortfolioIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioIngestionService.class);

    private final PortfolioCsvParser csvParser;
    private final CompanyResolutionService companyResolutionService;
    private final SecTickerIndexService tickerIndexService;
    private final SecEdgarClient secEdgarClient;
    private final GeminiExtractionService geminiExtractionService;
    private final HoldingRepository holdingRepository;
    private final ProcessedInputRepository processedInputRepository;
    private final AnalysisProgressTracker progressTracker;
    private final com.truesight.backend.risk.RiskScoringService riskScoringService;
    private final com.truesight.backend.repository.RelationshipRepository relationshipRepository;

    public PortfolioIngestionService(
            PortfolioCsvParser csvParser,
            CompanyResolutionService companyResolutionService,
            SecTickerIndexService tickerIndexService,
            SecEdgarClient secEdgarClient,
            GeminiExtractionService geminiExtractionService,
            HoldingRepository holdingRepository,
            ProcessedInputRepository processedInputRepository,
            AnalysisProgressTracker progressTracker,
            com.truesight.backend.risk.RiskScoringService riskScoringService,
            com.truesight.backend.repository.RelationshipRepository relationshipRepository
    ) {
        this.relationshipRepository = relationshipRepository;
        this.csvParser = csvParser;
        this.companyResolutionService = companyResolutionService;
        this.tickerIndexService = tickerIndexService;
        this.secEdgarClient = secEdgarClient;
        this.geminiExtractionService = geminiExtractionService;
        this.holdingRepository = holdingRepository;
        this.processedInputRepository = processedInputRepository;
        this.progressTracker = progressTracker;
        this.riskScoringService = riskScoringService;
    }

    // ============================================================================
    // Preview (AC 2.1: "shows a preview table of parsed rows; user confirms before
    // analysis starts"). Parsing here does NOT touch the database or SEC — it is
    // pure, side-effect-free, so previewing a CSV twice or abandoning it costs nothing.
    // ============================================================================

    public record PreviewRow(int rowNumber, String ticker, String companyName,
                              BigDecimal weightPercent, BigDecimal shares, BigDecimal marketValue,
                              boolean recognisedBySec, String secCompanyName) {
    }

    public record PreviewResult(List<PreviewRow> rows,
                                 List<CsvParseResult.DuplicateWarning> duplicateWarnings,
                                 List<CsvParseResult.SkippedRow> skippedNonEquityRows,
                                 List<String> unrecognisedTickers) {
    }

    /**
     * AC 2.1's "Rows whose ticker is not found in the SEC ticker index are listed as
     * unrecognised and can be skipped or corrected; they are never silently dropped" —
     * this is where that check happens, one layer above PortfolioCsvParser (which has
     * no SEC dependency at all — see CsvParseResult's Javadoc for why the split).
     */
    public PreviewResult preview(String csvContent) {
        CsvParseResult parsed = csvParser.parse(csvContent);
        List<PreviewRow> previewRows = new ArrayList<>();
        List<String> unrecognised = new ArrayList<>();

        for (ParsedCsvRow row : parsed.rows()) {
            var secEntry = tickerIndexService.findByTicker(row.ticker());
            boolean recognised = secEntry.isPresent();
            if (!recognised) {
                unrecognised.add(row.ticker());
            }
            previewRows.add(new PreviewRow(
                    row.rowNumber(), row.ticker(), row.companyName(),
                    row.weightPercent(), row.shares(), row.marketValue(),
                    recognised, secEntry.map(e -> e.companyName()).orElse(null)
            ));
        }

        return new PreviewResult(previewRows, parsed.duplicateWarnings(), parsed.skippedNonEquityRows(), unrecognised);
    }

    // ============================================================================
    // Confirm and apply: creates/updates Holding rows, THEN kicks off analysis.
    // ============================================================================

    /**
     * Applies confirmed rows as Holding rows (creating each row's Company via
     * CompanyResolutionService), then synchronously runs analysis across all of them.
     * "Synchronously" here means within this method call — the HTTP request this is
     * invoked from is expected to be long-running while progress is polled via
     * AnalysisProgressTracker from a separate endpoint; a production system would more
     * likely hand this off to an async task executor so the initiating request returns
     * immediately, which is a reasonable next step but not implemented here to keep
     * the control flow (and therefore the cancel-checking logic) in one obviously
     * readable place for a reference implementation.
     */
    // ---- AC 2.6: diff a re-upload against the current book, before applying --------

    public record DiffRow(String ticker, String companyName, BigDecimal currentWeight, BigDecimal newWeight) {
    }

    public record UploadDiff(List<DiffRow> added, List<DiffRow> removed, List<DiffRow> weightChanged, List<DiffRow> unchanged) {
    }

    @Transactional(readOnly = true)
    public UploadDiff diff(Portfolio portfolio, List<ParsedCsvRow> rows) {
        var current = new java.util.LinkedHashMap<String, Holding>();
        for (Holding h : holdingRepository.findActiveByPortfolioId(portfolio.getId())) {
            if (h.getCompany().getTicker() != null) {
                current.put(h.getCompany().getTicker().toUpperCase(), h);
            }
        }
        List<DiffRow> added = new ArrayList<>();
        List<DiffRow> changed = new ArrayList<>();
        List<DiffRow> unchanged = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (ParsedCsvRow row : rows) {
            String t = row.ticker().toUpperCase();
            seen.add(t);
            Holding h = current.get(t);
            if (h == null) {
                added.add(new DiffRow(t, row.companyName(), null, row.weightPercent()));
            } else if (!java.util.Objects.equals(scale(h.getWeightPercent()), scale(row.weightPercent()))) {
                changed.add(new DiffRow(t, h.getCompany().getName(), h.getWeightPercent(), row.weightPercent()));
            } else {
                unchanged.add(new DiffRow(t, h.getCompany().getName(), h.getWeightPercent(), row.weightPercent()));
            }
        }
        List<DiffRow> removed = new ArrayList<>();
        for (var en : current.entrySet()) {
            if (!seen.contains(en.getKey())) {
                removed.add(new DiffRow(en.getKey(), en.getValue().getCompany().getName(),
                        en.getValue().getWeightPercent(), null));
            }
        }
        return new UploadDiff(added, removed, changed, unchanged);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros();
    }

    @Transactional
    public List<Holding> applyAndAnalyse(User requestingUser, Portfolio portfolio, List<ParsedCsvRow> confirmedRows) {
        return applyAndAnalyse(requestingUser, portfolio, confirmedRows, false);
    }

    @Transactional
    public List<Holding> applyAndAnalyse(User requestingUser, Portfolio portfolio, List<ParsedCsvRow> confirmedRows,
                                         boolean removeMissing) {
        if (removeMissing) {
            // AC 2.6 replace semantics: soft-remove (undoable) anything not in the new
            // CSV. Holdings that ARE in it are updated in place below, so their reviews,
            // dismissed alerts and relationship overrides survive.
            var keep = new java.util.HashSet<String>();
            for (ParsedCsvRow row : confirmedRows) {
                keep.add(row.ticker().toUpperCase());
            }
            for (Holding h : holdingRepository.findActiveByPortfolioId(portfolio.getId())) {
                String t = h.getCompany().getTicker();
                if (t == null || !keep.contains(t.toUpperCase())) {
                    h.setRemoved(true);
                }
            }
        }
        List<Holding> holdings = new ArrayList<>();
        for (ParsedCsvRow row : confirmedRows) {
            holdings.add(createOrUpdateHolding(portfolio, row));
        }

        progressTracker.start(portfolio.getId(), holdings.size());
        try {
            for (int i = 0; i < holdings.size(); i++) {
                if (progressTracker.isCancelRequested(portfolio.getId())) {
                    // AC 2.5: "Cancel stops any further SEC and LLM calls; holdings
                    // already analysed remain usable." Holdings not yet reached simply
                    // stay PENDING — never marked Failed, since nothing about them
                    // failed, the user just stopped waiting.
                    log.info("Analysis cancelled for portfolio {}, {} holding(s) left unprocessed",
                            portfolio.getId(), holdings.size() - i);
                    break;
                }
                analyseOneHolding(requestingUser, holdings.get(i));
            }
        } finally {
            progressTracker.finish(portfolio.getId());
        }

        portfolio.setLastAnalysedAt(java.time.Instant.now());
        // Scores are derived from relationships, so they are (re)computed only after
        // extraction, never during — a half-analysed portfolio would otherwise show
        // scores computed against an incomplete graph.
        riskScoringService.rescorePortfolio(portfolio.getId(), requestingUser.getId(), "Portfolio analysis");
        return holdings;
    }

    /**
     * AC 2.3: "add a single company by ticker... analysis for the new holding starts
     * automatically and shows progress." A one-holding special case of
     * applyAndAnalyse's shape — new Holding, then straight into analyseOneHolding —
     * with two differences AC 2.3 specifically calls for that the CSV path doesn't:
     * the empty-Optional case here is a genuine user-facing outcome (an unknown ticker
     * typed by hand should show "not found in SEC index" INLINE, not throw), and the
     * resulting holding is flagged newlyAdded so it can be highlighted in the list and
     * graph until the user's next session (see Holding.newlyAdded's Javadoc).
     */
    @Transactional
    public Optional<Holding> addSingleHoldingByTicker(User requestingUser, Portfolio portfolio, String ticker) {
        Optional<Company> resolved = companyResolutionService.resolveByTicker(ticker);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Company company = resolved.get();

        Holding holding = holdingRepository.findActiveByPortfolioIdAndCompanyId(portfolio.getId(), company.getId())
                .orElseGet(() -> new Holding(portfolio, company));
        holding.setCoverageStatus(CoverageStatus.PENDING);
        holding.setNewlyAdded(true);
        holding = holdingRepository.save(holding);

        progressTracker.start(portfolio.getId(), 1);
        try {
            analyseOneHolding(requestingUser, holding);
        } finally {
            progressTracker.finish(portfolio.getId());
        }
        portfolio.setLastAnalysedAt(java.time.Instant.now());
        riskScoringService.rescorePortfolio(portfolio.getId(), requestingUser.getId(),
                "Added holding " + company.getTicker());
        return Optional.of(holding);
    }

    public record ExpandOutcome(boolean analysed, boolean cached, String message) {
    }

    /**
     * AC 4.8: "expand a supplier to reveal tier-2 dependencies... Results are cached so
     * a second expansion is instant." Analyses a supplier company's own primary filing
     * (it must have a CIK) exactly as a holding would be, then rescores. The cache is
     * ProcessedInput keyed by accession number: a second call finds the filing already
     * processed and returns immediately without SEC or LLM calls.
     */
    @Transactional
    public ExpandOutcome expandSupplier(User requestingUser, Portfolio portfolio, Company company) {
        if (company.getCik() == null) {
            return new ExpandOutcome(false, false, company.getName() + " has no SEC filings, so its own suppliers cannot be extracted.");
        }
        try {
            List<SecFilingSummary> filings = secEdgarClient.listRecentFilings(company.getCik());
            if (filings.isEmpty()) {
                company.setHasNoSecFilings(true);
                return new ExpandOutcome(false, false, "SEC EDGAR has no relevant filings for " + company.getName() + ".");
            }
            SecFilingSummary primary = SecEdgarClient.pickPrimaryFiling(filings);
            if (processedInputRepository.existsByInputKey(primary.accessionNumber())) {
                return new ExpandOutcome(false, true, company.getName() + " was already analysed (filing " + primary.accessionNumber() + "); showing cached results.");
            }
            FetchedFiling filing = secEdgarClient.fetchFilingText(company.getCik(), primary);
            GeminiExtractionService.ExtractionOutcome out = geminiExtractionService.extractAndPersist(requestingUser, company, filing);
            ProcessedInput processed = new ProcessedInput(ProcessedInputType.SEC_FILING, primary.accessionNumber());
            processed.setRelationshipsFound(out.relationshipsPersisted());
            processedInputRepository.save(processed);
            riskScoringService.rescorePortfolio(portfolio.getId(), requestingUser.getId(), "Expanded " + company.getName());
            return new ExpandOutcome(true, false, out.relationshipsPersisted() + " verified relationship(s) found for " + company.getName()
                    + (out.excerptsRejected() > 0 ? " (" + out.excerptsRejected() + " excerpt(s) failed verification and were discarded)" : "") + ".");
        } catch (Exception e) {
            throw new RuntimeException(describeFailure(e), e);
        }
    }

    public record RefreshOutcome(int holdingsChecked, int reanalysed, int unchanged, int failed,
                                 int relationshipsMarkedNoLongerDisclosed, boolean cancelled) {
    }

    /**
     * Epic 8: re-analyse only what changed. For each active holding, look up its
     * primary filing again; if that accession number has already been processed,
     * nothing is fetched and no LLM call is made (AC 8.1: "only changed inputs are
     * re-analysed... this keeps LLM spend bounded"). If it is new, extract from it and
     * then apply AC 5.3: any relationship into this holding whose evidence comes only
     * from OLDER filings — i.e. the new filing did not re-mention it — is flagged
     * noLongerDisclosed and drops out of scoring until the user confirms it.
     *
     * <p>Failure handling is the whole point of AC 8.1's last line ("a refresh that
     * fails leaves the previous results in place and shows a banner"): a holding whose
     * re-analysis fails keeps its previous COVERED status and relationships — its
     * coverage is NOT flipped to FAILED, because the old analysis is still valid. The
     * failure is recorded on the LLM health tracker (banner) and in the audit log.
     */
    @Transactional
    public RefreshOutcome refreshChangedFilings(User requestingUser, Portfolio portfolio) {
        List<Holding> holdings = holdingRepository.findActiveByPortfolioId(portfolio.getId());
        int reanalysed = 0;
        int unchanged = 0;
        int failed = 0;
        int flagged = 0;
        boolean cancelled = false;

        progressTracker.start(portfolio.getId(), holdings.size());
        try {
            for (Holding holding : holdings) {
                if (progressTracker.isCancelRequested(portfolio.getId())) {
                    cancelled = true;
                    break;
                }
                Company company = holding.getCompany();
                if (company.getCik() == null) {
                    unchanged++;
                    progressTracker.recordCompleted(portfolio.getId());
                    continue;
                }
                try {
                    List<SecFilingSummary> filings = secEdgarClient.listRecentFilings(company.getCik());
                    SecFilingSummary primary = filings.isEmpty() ? null : SecEdgarClient.pickPrimaryFiling(filings);
                    if (primary == null || processedInputRepository.existsByInputKey(primary.accessionNumber())) {
                        unchanged++;
                        progressTracker.recordCompleted(portfolio.getId());
                        continue;
                    }
                    FetchedFiling filing = secEdgarClient.fetchFilingText(company.getCik(), primary);
                    geminiExtractionService.extractAndPersist(requestingUser, company, filing);
                    ProcessedInput processed = new ProcessedInput(ProcessedInputType.SEC_FILING, primary.accessionNumber());
                    processedInputRepository.save(processed);
                    flagged += flagNoLongerDisclosed(company, primary.accessionNumber());
                    holding.setLastAnalysedAt(java.time.Instant.now());
                    holding.setCoverageStatus(CoverageStatus.COVERED);
                    holding.setFailureReason(null);
                    reanalysed++;
                    progressTracker.recordCompleted(portfolio.getId());
                } catch (Exception e) {
                    // Previous results stay; only the attempt is recorded (see Javadoc).
                    failed++;
                    log.warn("Refresh failed for {} — previous analysis kept: {}", company.getName(), describeFailure(e));
                    progressTracker.recordFailed(portfolio.getId());
                }
            }
        } finally {
            progressTracker.finish(portfolio.getId());
        }
        if (reanalysed > 0) {
            portfolio.setLastAnalysedAt(java.time.Instant.now());
            riskScoringService.rescorePortfolio(portfolio.getId(), requestingUser.getId(),
                    "Refresh: " + reanalysed + " new filing(s)");
        }
        return new RefreshOutcome(holdings.size(), reanalysed, unchanged, failed, flagged, cancelled);
    }

    /**
     * AC 5.3: a relationship into {@code filer} with no evidence from the newest
     * processed filing is "no longer disclosed". Only edges whose latest evidence is
     * from a filing of the same form family are flagged — a 10-Q that omits a supplier
     * named in the 10-K is normal (quarterlies are shorter), and must not flag it.
     */
    private int flagNoLongerDisclosed(Company filer, String newAccession) {
        int flagged = 0;
        var newEvidenceType = relationshipRepository.findByToCompanyId(filer.getId()).stream()
                .flatMap(r -> r.getEvidence().stream())
                .filter(ev -> newAccession.equals(ev.getAccessionNumber()))
                .map(ev -> ev.getSourceType()).findFirst().orElse(null);
        if (newEvidenceType == null) {
            return 0; // the new filing yielded nothing at all; don't infer anything from silence
        }
        for (var r : relationshipRepository.findByToCompanyId(filer.getId())) {
            boolean mentionedInNew = r.getEvidence().stream().anyMatch(ev -> newAccession.equals(ev.getAccessionNumber()));
            boolean sameFamilyBefore = r.getEvidence().stream().anyMatch(ev -> ev.getSourceType() == newEvidenceType);
            if (!mentionedInNew && sameFamilyBefore && !r.isNoLongerDisclosed()) {
                r.setNoLongerDisclosed(true);
                r.touch();
                flagged++;
            } else if (mentionedInNew && r.isNoLongerDisclosed()) {
                r.setNoLongerDisclosed(false); // re-disclosed: clear the flag
                r.touch();
            }
        }
        return flagged;
    }

    private Holding createOrUpdateHolding(Portfolio portfolio, ParsedCsvRow row) {
        Optional<Company> resolved = companyResolutionService.resolveByTicker(row.ticker());
        if (resolved.isEmpty()) {
            // AC 2.1: unrecognised tickers were already surfaced at preview time; if
            // the caller confirmed anyway (e.g. explicitly chose to include it), we
            // still cannot create a Holding with no Company to attach it to. This
            // reference implementation treats that as the caller's responsibility to
            // have filtered out at confirm time — a stricter version could instead
            // create a placeholder Company here, deliberately not done because that
            // would mean silently fabricating a company record for a ticker SEC has
            // never heard of, which the "no fabricated data" rule argues against.
            throw new IllegalArgumentException("Ticker " + row.ticker() + " is not in the SEC index and cannot be added");
        }
        Company company = resolved.get();

        Holding holding = holdingRepository.findActiveByPortfolioIdAndCompanyId(portfolio.getId(), company.getId())
                .orElseGet(() -> new Holding(portfolio, company));
        holding.setWeightPercent(row.weightPercent());
        holding.setShares(row.shares());
        holding.setMarketValue(row.marketValue());
        holding.setCoverageStatus(CoverageStatus.PENDING);
        holding.setFailureReason(null);
        return holdingRepository.save(holding);
    }

    /**
     * One holding, start to finish: fetch its latest relevant filing, skip Gemini
     * entirely if that exact filing was already processed (AC 8.1), extract and
     * persist relationships, update coverage status. Every exit path sets a final
     * coverage status — a holding is never left PENDING after this method returns
     * normally, so "in progress" in the UI always means "genuinely still running",
     * never "finished but forgot to update status".
     */
    private void analyseOneHolding(User requestingUser, Holding holding) {
        Company company = holding.getCompany();
        try {
            if (company.getCik() == null) {
                // No CIK at all (private company, or SEC index lookup never resolved
                // one) — AC 2.2's NO_SEC_FILINGS state, not a failure.
                markNoSecFilings(holding);
                return;
            }

            List<SecFilingSummary> filings = secEdgarClient.listRecentFilings(company.getCik());
            if (filings.isEmpty()) {
                markNoSecFilings(holding);
                return;
            }

            // Annual report first, not simply the newest filing — see SecEdgarClient#pickPrimaryFiling.
            SecFilingSummary mostRecent = SecEdgarClient.pickPrimaryFiling(filings);
            String processedInputKey = mostRecent.accessionNumber();

            if (processedInputRepository.existsByInputKey(processedInputKey)) {
                // AC 8.1: "Unchanged inputs use cached results." The relationships
                // this filing already produced (if any) are already persisted from a
                // prior run — nothing further to do, and no further LLM spend.
                log.debug("Filing {} already processed, skipping re-extraction for {}", processedInputKey, company.getName());
                markCovered(holding);
                return;
            }

            FetchedFiling filing = secEdgarClient.fetchFilingText(company.getCik(), mostRecent);
            GeminiExtractionService.ExtractionOutcome outcome =
                    geminiExtractionService.extractAndPersist(requestingUser, company, filing);

            ProcessedInput processedInput = new ProcessedInput(ProcessedInputType.SEC_FILING, processedInputKey);
            processedInput.setRelationshipsFound(outcome.relationshipsPersisted());
            processedInputRepository.save(processedInput);

            markCovered(holding);
        } catch (Exception e) {
            // AC 2.5: "Failed holdings are listed with a reason (SEC unreachable, LLM
            // error, no filings)." The exact exception message is surfaced as the
            // reason rather than a generic "something went wrong" — AC 10.1 requires
            // failures to name their cause, and swallowing the real message here would
            // violate that even though the code technically "handles" the error.
            holding.setCoverageStatus(CoverageStatus.FAILED);
            holding.setFailureReason(describeFailure(e));
            log.warn("Analysis failed for holding {} ({})", company.getName(), company.getTicker(), e);
            progressTracker.recordFailed(holding.getPortfolio().getId());
        }
    }

    // markCovered/markNoSecFilings both call recordCompleted THEMSELVES, rather than
    // leaving each call site in analyseOneHolding responsible for remembering to call
    // it — an earlier version left the call at only one of the three places these two
    // methods are invoked from (the "already processed, skip re-extraction" branch
    // silently never recorded progress, which would have hung the AC 2.5 progress bar
    // at "x of N" forever for any portfolio where an already-cached filing was hit).
    // Centralising the call here makes that class of bug structurally impossible:
    // every successful, non-failure exit from analyseOneHolding goes through one of
    // these two methods, and both of them count themselves.

    private void markCovered(Holding holding) {
        holding.setCoverageStatus(CoverageStatus.COVERED);
        holding.setLastAnalysedAt(java.time.Instant.now());
        progressTracker.recordCompleted(holding.getPortfolio().getId());
    }

    private void markNoSecFilings(Holding holding) {
        holding.setCoverageStatus(CoverageStatus.NO_SEC_FILINGS);
        holding.setLastAnalysedAt(java.time.Instant.now());
        progressTracker.recordCompleted(holding.getPortfolio().getId());
    }

    /**
     * AC 2.5 / AC 10.1: a failure names its cause. Walks the cause chain for a typed
     * LLM failure first, because that is the most specific (and most actionable)
     * thing we can say — "quota exhausted" tells the user something "analysis error"
     * never could. A plain IOException at the top level is an SEC fetch problem
     * (Gemini failures are always wrapped as LlmUnavailableException by GeminiClient).
     */
    private String describeFailure(Exception e) {
        var llm = com.truesight.backend.ingestion.llm.LlmUnavailableException.findIn(e);
        if (llm != null) {
            return llm.userFacingSummary();
        }
        if (e instanceof java.io.IOException) {
            return "SEC EDGAR unreachable: " + e.getMessage();
        }
        return "Analysis error: " + e.getMessage();
    }
}
