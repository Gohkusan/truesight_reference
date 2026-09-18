package com.truesight.backend.web;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.risk.RiskQueryService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.HoldingService;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.GraphResponse;
import com.truesight.backend.web.dto.RiskResponses.RiskItem;
import com.truesight.backend.web.dto.RiskResponses.Summary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC 10.5: export findings as CSV or PDF. Both exports carry the same disclaimer the
 * UI shows (AC 11.1), and the PDF prints every number next to its source: score with
 * its factors, shared suppliers with their holdings. The PDF is plain and monochrome
 * on purpose — the old prototype's "board-ready memorandum" styling is the kind of
 * decoration design principle 10 asks us to remove.
 */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/export")
@Tag(name = "Export", description = "Findings as CSV or PDF (AC 10.5).")
public class ExportController {

    private final PortfolioService portfolioService;
    private final HoldingService holdingService;
    private final RiskQueryService riskQueryService;
    private final GraphAssemblyService graphAssemblyService;
    private final CurrentUser currentUser;

    public ExportController(PortfolioService portfolioService, HoldingService holdingService,
                            RiskQueryService riskQueryService, GraphAssemblyService graphAssemblyService,
                            CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.holdingService = holdingService;
        this.riskQueryService = riskQueryService;
        this.graphAssemblyService = graphAssemblyService;
        this.currentUser = currentUser;
    }

    @GetMapping(value = "/risks.csv", produces = "text/csv")
    @Operation(summary = "Ranked risks as CSV")
    public ResponseEntity<byte[]> risksCsv(@PathVariable Long portfolioId) {
        Portfolio p = portfolioService.getOwned(portfolioId, currentUser.id());
        StringBuilder sb = new StringBuilder("kind,title,score,severity,trend,affectedHoldings,totalWeightExposed,factors\r\n");
        for (RiskItem r : riskQueryService.list(portfolioId, currentUser.id(), null, null)) {
            sb.append(r.kind()).append(',').append(csv(r.title())).append(',')
              .append(r.score() == null ? "" : r.score()).append(',').append(r.severity()).append(',').append(r.trend()).append(',')
              .append(r.affectedHoldings().size()).append(',').append(r.totalWeightExposed()).append(',')
              .append(csv(String.join(" | ", r.factors()))).append("\r\n");
        }
        sb.append("\r\n# ").append(RecommendationController.DISCLAIMER).append("\r\n");
        return file(sb.toString().getBytes(StandardCharsets.UTF_8), "truesight-" + slug(p.getName()) + "-risks.csv", "text/csv");
    }

    @GetMapping(value = "/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Findings report as PDF: summary, holdings, ranked risks with factors, shared suppliers")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> reportPdf(@PathVariable Long portfolioId) {
        Long userId = currentUser.id();
        Portfolio p = portfolioService.getOwned(portfolioId, userId);
        Summary summary = riskQueryService.summary(portfolioId, userId);
        List<RiskItem> risks = riskQueryService.list(portfolioId, userId, null, null);
        GraphResponse graph = graphAssemblyService.buildResponse(portfolioId, userId, false);

        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 9.5f);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("TrueSight findings — " + p.getName(), h1));
        doc.add(new Paragraph("Generated " + Instant.now() + ". Last analysed " + p.getLastAnalysedAt() + ".", small));
        doc.add(new Paragraph(RecommendationController.DISCLAIMER, small));
        doc.add(new Paragraph(" "));

        doc.add(new Paragraph("Composite risk", h2));
        doc.add(new Paragraph((summary.compositeScore() == null ? "Unknown" : summary.compositeScore() + "/100 " + summary.band())
                + " — " + summary.scoredHoldings() + " of " + summary.totalHoldings() + " holdings scored, "
                + summary.unknownHoldings() + " unknown.", body));
        doc.add(new Paragraph(summary.howComputed(), small));
        doc.add(new Paragraph(" "));

        doc.add(new Paragraph("Holdings", h2));
        PdfPTable ht = new PdfPTable(new float[]{1.2f, 3f, 1f, 1.6f});
        ht.setWidthPercentage(100);
        for (String h : List.of("Ticker", "Name", "Weight %", "Coverage")) {
            ht.addCell(header(h, body));
        }
        holdingService.listActive(portfolioId).forEach(h -> {
            ht.addCell(cell(h.getCompany().getTicker(), body));
            ht.addCell(cell(h.getCompany().getName(), body));
            ht.addCell(cell(h.getWeightPercent() == null ? "" : h.getWeightPercent().toPlainString(), body, Element.ALIGN_RIGHT));
            ht.addCell(cell(h.getCoverageStatus().name(), body));
        });
        doc.add(ht);
        doc.add(new Paragraph(" "));

        doc.add(new Paragraph("Ranked risks", h2));
        for (RiskItem r : risks) {
            doc.add(new Paragraph(r.title() + " — " + (r.score() == null ? "Unknown" : r.score() + "/100 " + r.severity())
                    + " (" + r.trend() + ")", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f)));
            for (String f : r.factors()) {
                doc.add(new Paragraph("   • " + f, body));
            }
        }
        doc.add(new Paragraph(" "));

        doc.add(new Paragraph("Shared suppliers", h2));
        if (graph.sharedSuppliers().isEmpty()) {
            doc.add(new Paragraph("None: no supplier is connected to two or more holdings.", body));
        } else {
            for (GraphResponse.SharedSupplier s : graph.sharedSuppliers()) {
                doc.add(new Paragraph(s.name() + ": " + s.holdingCount() + " holdings (" + String.join(", ", s.holdingTickers())
                        + "), " + s.combinedWeightPercent() + "% combined weight", body));
            }
        }
        doc.close();
        return file(out.toByteArray(), "truesight-" + slug(p.getName()) + "-report.pdf", MediaType.APPLICATION_PDF_VALUE);
    }

    private static PdfPCell header(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(new Color(235, 235, 240));
        c.setPadding(4);
        return c;
    }

    private static PdfPCell cell(String text, Font f) {
        return cell(text, f, Element.ALIGN_LEFT);
    }

    private static PdfPCell cell(String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        return c;
    }

    private static ResponseEntity<byte[]> file(byte[] bytes, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        return (v.contains(",") || v.contains("\"") || v.contains("\n")) ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
