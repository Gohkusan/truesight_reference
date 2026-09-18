package com.truesight.backend.web;

import com.truesight.backend.common.ValidationException;
import com.truesight.backend.domain.Holding;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.AnalysisProgressTracker;
import com.truesight.backend.ingestion.PortfolioIngestionService;
import com.truesight.backend.ingestion.csv.CsvParseResult;
import com.truesight.backend.ingestion.csv.ParsedCsvRow;
import com.truesight.backend.ingestion.csv.PortfolioCsvParser;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.HoldingService;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.AddByTickerRequest;
import com.truesight.backend.web.dto.AnalysisProgressResponse;
import com.truesight.backend.web.dto.ConfirmUploadRequest;
import com.truesight.backend.web.dto.CsvPreviewResponse;
import com.truesight.backend.web.dto.HoldingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Epic 2's endpoints. Every method resolves the portfolio via
 * PortfolioService#getOwned first — a request for holdings under a portfolio id that
 * doesn't belong to the caller 404s before any holding-level logic runs, so the
 * ownership check protects this entire controller, not just the portfolio's own CRUD.
 */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/holdings")
@Tag(name = "Holdings", description = "CSV upload, preview/confirm, single-ticker add, progress and cancel (Epic 2).")
public class HoldingController {

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024; // AC 2.1: 5 MB cap

    private final PortfolioService portfolioService;
    private final HoldingService holdingService;
    private final PortfolioCsvParser csvParser;
    private final PortfolioIngestionService ingestionService;
    private final AnalysisProgressTracker progressTracker;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public HoldingController(
            PortfolioService portfolioService,
            HoldingService holdingService,
            PortfolioCsvParser csvParser,
            PortfolioIngestionService ingestionService,
            AnalysisProgressTracker progressTracker,
            UserRepository userRepository,
            CurrentUser currentUser
    ) {
        this.portfolioService = portfolioService;
        this.holdingService = holdingService;
        this.csvParser = csvParser;
        this.ingestionService = ingestionService;
        this.progressTracker = progressTracker;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List holdings", description = "AC 2.2: ticker, name, weight %, coverage status, last analysed time.")
    public List<HoldingResponse> list(@PathVariable Long portfolioId) {
        Portfolio portfolio = portfolioService.getOwned(portfolioId, currentUser.id());
        return holdingService.listActive(portfolio.getId()).stream().map(HoldingResponse::from).toList();
    }

    @PostMapping("/upload/preview")
    @Operation(summary = "Preview a CSV upload", description =
            "AC 2.1: parses and validates without writing anything. Returns parsed rows, duplicate "
            + "warnings, skipped non-equity rows, and unrecognised tickers for the user to review "
            + "before confirming.")
    public CsvPreviewResponse previewUpload(@PathVariable Long portfolioId, @RequestParam("file") MultipartFile file) {
        portfolioService.getOwned(portfolioId, currentUser.id()); // ownership check even though preview writes nothing — 404 before any file processing
        validateUploadFile(file);
        String content = readAsUtf8(file);
        PortfolioIngestionService.PreviewResult result = ingestionService.preview(content);
        return CsvPreviewResponse.from(result, content);
    }

    @PostMapping("/upload/confirm")
    @Operation(summary = "Confirm a previewed CSV and start analysis", description =
            "AC 2.1/2.5: applies the confirmed rows as holdings and runs SEC+Gemini analysis, "
            + "reporting progress via GET .../analysis/progress and cancellable via POST .../analysis/cancel.")
    public List<HoldingResponse> confirmUpload(@PathVariable Long portfolioId, @Valid @RequestBody ConfirmUploadRequest request) {
        Portfolio portfolio = portfolioService.getOwned(portfolioId, currentUser.id());
        User user = userRepository.getReferenceById(currentUser.id());

        CsvParseResult parsed = csvParser.parse(request.csvContent());
        Set<String> excluded = request.excludedTickers() == null
                ? Set.of() : Set.copyOf(request.excludedTickers());
        List<ParsedCsvRow> confirmedRows = parsed.rows().stream()
                .filter(row -> !excluded.contains(row.ticker()))
                .toList();

        List<Holding> holdings = ingestionService.applyAndAnalyse(user, portfolio, confirmedRows, request.removeMissingOrDefault());
        return holdings.stream().map(HoldingResponse::from).toList();
    }

    @PostMapping("/upload/diff")
    @Operation(summary = "Diff a re-upload against the current holdings before applying (AC 2.6)",
            description = "Added, removed, and weight-changed holdings. Nothing is written. Apply with confirm and "
                    + "removeMissing=true to replace the book; reviews, dismissals and overrides on remaining holdings are kept.")
    public PortfolioIngestionService.UploadDiff diff(@PathVariable Long portfolioId, @Valid @RequestBody ConfirmUploadRequest request) {
        Portfolio portfolio = portfolioService.getOwned(portfolioId, currentUser.id());
        CsvParseResult parsed = csvParser.parse(request.csvContent());
        Set<String> excluded = request.excludedTickers() == null ? Set.of() : Set.copyOf(request.excludedTickers());
        return ingestionService.diff(portfolio, parsed.rows().stream().filter(r -> !excluded.contains(r.ticker())).toList());
    }

    @PostMapping("/add-by-ticker")
    @Operation(summary = "Add a single holding by ticker", description =
            "AC 2.3: search-as-you-type is a separate endpoint (see CompanySearchController); this "
            + "adds the confirmed ticker as a holding and starts analysis immediately.")
    public ResponseEntity<HoldingResponse> addByTicker(@PathVariable Long portfolioId, @Valid @RequestBody AddByTickerRequest request) {
        Portfolio portfolio = portfolioService.getOwned(portfolioId, currentUser.id());
        User user = userRepository.getReferenceById(currentUser.id());

        return ingestionService.addSingleHoldingByTicker(user, portfolio, request.ticker())
                .map(h -> ResponseEntity.ok(HoldingResponse.from(h)))
                .orElseThrow(() -> new ValidationException(
                        "Ticker \"" + request.ticker() + "\" was not found in the SEC index"));
    }

    @DeleteMapping("/{holdingId}")
    @Operation(summary = "Remove a holding", description = "AC 2.4: soft-removed; undo available for the rest of the session.")
    public ResponseEntity<Void> remove(@PathVariable Long portfolioId, @PathVariable Long holdingId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        holdingService.remove(holdingId, portfolioId, currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{holdingId}/undo-remove")
    @Operation(summary = "Undo a holding removal", description = "AC 2.4/10.4.")
    public ResponseEntity<Void> undoRemove(@PathVariable Long portfolioId, @PathVariable Long holdingId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        holdingService.undoRemove(holdingId, portfolioId, currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/analysis/progress")
    @Operation(summary = "Poll analysis progress", description = "AC 2.5: 'x of N holdings analysed' and completion state.")
    public AnalysisProgressResponse progress(@PathVariable Long portfolioId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return AnalysisProgressResponse.from(progressTracker.getProgress(portfolioId));
    }

    @PostMapping("/analysis/cancel")
    @Operation(summary = "Cancel in-progress analysis", description = "AC 2.5: stops further SEC/LLM calls; already-analysed holdings remain usable.")
    public ResponseEntity<Void> cancel(@PathVariable Long portfolioId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        progressTracker.requestCancel(portfolioId);
        return ResponseEntity.accepted().build();
    }

    private void validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("No file was uploaded");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ValidationException("File exceeds the 5 MB upload limit");
        }
        String filename = file.getOriginalFilename();
        boolean looksLikeCsv = filename != null && filename.toLowerCase().endsWith(".csv");
        String contentType = file.getContentType();
        boolean contentTypeLooksLikeCsv = contentType == null
                || contentType.contains("csv") || contentType.equals("text/plain")
                || contentType.equals("application/vnd.ms-excel") // some browsers report CSV this way
                || contentType.equals("application/octet-stream"); // and some report it this way
        if (!looksLikeCsv && !contentTypeLooksLikeCsv) {
            throw new ValidationException("Only CSV files are accepted. JSON and other formats are not supported.");
        }
    }

    private String readAsUtf8(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ValidationException("Could not read the uploaded file");
        }
    }
}
