package com.truesight.backend.ingestion.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FilingSectionSelectorTest {

    private static String fakeTenK() {
        StringBuilder sb = new StringBuilder();
        sb.append("UNITED STATES SECURITIES AND EXCHANGE COMMISSION\nFORM 10-K\n");
        sb.append("COVER PAGE BOILERPLATE ".repeat(300)).append('\n');
        // Table of contents: same headings, short bodies.
        sb.append("Item 1. Business 4\nItem 1A. Risk Factors 12\nItem 2. Properties 30\nItem 7. MD&A 40\n\n");
        sb.append("Item 1. Business\n").append("We design chips. ".repeat(200)).append('\n');
        sb.append("Item 1A. Risk Factors\n").append("We depend on a single foundry partner. ".repeat(200)).append('\n');
        sb.append("Item 2. Properties\n").append("We lease offices. ".repeat(200)).append('\n');
        sb.append("Item 7. Management's Discussion\n").append("Revenue grew. ".repeat(200)).append('\n');
        return sb.toString();
    }

    @Test
    void prefersRiskFactorsAndBusinessOverCoverPage() {
        String text = fakeTenK();
        String selected = FilingSectionSelector.select(text, 6000, false);
        assertThat(selected).contains("[Item 1A]").contains("single foundry partner");
        assertThat(selected).doesNotContain("COVER PAGE BOILERPLATE");
    }

    @Test
    void skipsTableOfContentsEntries() {
        String text = fakeTenK();
        var sections = FilingSectionSelector.findSections(text);
        long tocEntries = sections.stream().filter(s -> s.looksLikeTocEntry()).count();
        long real = sections.stream().filter(s -> !s.looksLikeTocEntry()).count();
        assertThat(tocEntries).isGreaterThanOrEqualTo(3);
        assertThat(real).isGreaterThanOrEqualTo(4);
        String selected = FilingSectionSelector.select(text, 6000, false);
        assertThat(selected).doesNotContain("Risk Factors 12");
    }

    @Test
    void respectsBudget() {
        String selected = FilingSectionSelector.select(fakeTenK(), 3000, false);
        assertThat(selected.length()).isLessThanOrEqualTo(3200); // small overhead for section labels
    }

    @Test
    void shortDocumentIsReturnedWhole() {
        String text = "Item 1A. Risk Factors\nShort.";
        assertThat(FilingSectionSelector.select(text, 60_000, false)).isEqualTo(text);
    }

    @Test
    void fallsBackPastTheCoverWhenNoHeadingsFound() {
        String text = "COVER ".repeat(2000) + "BODY " .repeat(20_000);
        String selected = FilingSectionSelector.select(text, 5000, false);
        assertThat(selected).hasSizeLessThanOrEqualTo(5000);
        assertThat(selected).doesNotStartWith("COVER COVER COVER COVER COVER COVER COVER COVER COVER COVER COVER");
    }
}
