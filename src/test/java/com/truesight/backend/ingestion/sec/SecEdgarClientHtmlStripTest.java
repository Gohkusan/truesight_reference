package com.truesight.backend.ingestion.sec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The HTML-to-text stripper is what ExcerptVerificationService's literal substring
 * search runs against — if it mangles a sentence the LLM might quote (wrong spacing,
 * a swallowed word, a missing paragraph break that jams two sentences together), a
 * genuinely true excerpt could fail verification and get wrongly discarded. This test
 * exists to keep that risk visible and checked, not just plausible-looking.
 */
class SecEdgarClientHtmlStripTest {

    @Test
    void stripsBasicTags() {
        String html = "<p>We rely on <b>Taiwan Semiconductor</b> as our sole foundry partner.</p>";
        assertThat(SecEdgarClient.stripHtmlToText(html))
                .isEqualTo("We rely on Taiwan Semiconductor as our sole foundry partner.");
    }

    @Test
    void removesScriptAndStyleBlocksEntirely() {
        String html = "<style>.x{color:red}</style><p>Real content here.</p><script>var x = 1;</script>";
        String result = SecEdgarClient.stripHtmlToText(html);
        assertThat(result).isEqualTo("Real content here.");
        assertThat(result).doesNotContain("color:red", "var x");
    }

    @Test
    void insertsNewlineBetweenBlockLevelParagraphs() {
        // Two adjacent <p> blocks must not run together into one unbroken sentence —
        // a quoted excerpt spanning what were two separate paragraphs would then
        // wrongly appear "found" as one contiguous string that never existed as such.
        String html = "<p>Item 1. Business.</p><p>We depend on a single supplier for a key component.</p>";
        String result = SecEdgarClient.stripHtmlToText(html);
        assertThat(result).contains("Item 1. Business.\n\nWe depend on a single supplier");
    }

    @Test
    void unescapesCommonEntities() {
        String html = "<p>Smith &amp; Wesson&rsquo;s revenue grew &lt;10%&gt; year over year.</p>";
        assertThat(SecEdgarClient.stripHtmlToText(html))
                .isEqualTo("Smith & Wesson’s revenue grew <10%> year over year.");
    }

    @Test
    void collapsesRepeatedWhitespaceButPreservesWordBoundaries() {
        String html = "<p>We    have     multiple   \t  suppliers.</p>";
        assertThat(SecEdgarClient.stripHtmlToText(html)).isEqualTo("We have multiple suppliers.");
    }

    @Test
    void handlesNbspAsASpaceNotADeletedCharacter() {
        // &nbsp; must become a real space, not vanish — "Item&nbsp;1A" must not
        // collapse into the single glued word "Item1A".
        String html = "<p>Item&nbsp;1A. Risk Factors.</p>";
        assertThat(SecEdgarClient.stripHtmlToText(html)).isEqualTo("Item 1A. Risk Factors.");
    }

    @Test
    void tableCellTextSurvivesEvenWithoutColumnStructure() {
        // Documented limitation (see stripHtmlToText's Javadoc): table structure is
        // lost, but the cell TEXT itself must still be present and findable, since a
        // quoted excerpt could originate from inside a table cell.
        String html = "<table><tr><td>Supplier</td><td>Taiwan Semiconductor Manufacturing</td></tr></table>";
        String result = SecEdgarClient.stripHtmlToText(html);
        assertThat(result).contains("Taiwan Semiconductor Manufacturing");
    }
}
