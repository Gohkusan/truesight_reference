package com.truesight.backend.ingestion.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The hallucination guard is the single piece of logic this whole application's
 * credibility rests on — see ExcerptVerificationService's class Javadoc. Every test
 * here maps to a concrete way a real Gemini response could differ cosmetically from
 * the source text (or, in the negative tests, a way a fabricated excerpt must still be
 * caught despite looking plausible).
 */
class ExcerptVerificationServiceTest {

    private final ExcerptVerificationService service = new ExcerptVerificationService();

    @Test
    void findsExactMatch() {
        String source = "We rely on a single supplier for our advanced packaging needs.";
        String excerpt = "We rely on a single supplier for our advanced packaging needs.";
        assertThat(service.isVerbatim(excerpt, source)).isTrue();
    }

    @Test
    void findsExcerptEmbeddedInLongerSource() {
        String source = "Item 1A. Risk Factors. Among other things, we rely on a single supplier "
                + "for our advanced packaging needs, which could disrupt production if interrupted. "
                + "We also face currency risk.";
        String excerpt = "we rely on a single supplier for our advanced packaging needs";
        assertThat(service.isVerbatim(excerpt, source)).isTrue();
    }

    @Test
    void toleratesCurlyVsStraightDoubleQuotes() {
        String source = "The agreement is described as a “sole-source” arrangement.";
        String excerptWithStraightQuotes = "The agreement is described as a \"sole-source\" arrangement.";
        assertThat(service.isVerbatim(excerptWithStraightQuotes, source)).isTrue();
    }

    @Test
    void toleratesCurlyVsStraightSingleQuotesAndApostrophes() {
        String source = "the company’s largest supplier";
        String excerpt = "the company's largest supplier";
        assertThat(service.isVerbatim(excerpt, source)).isTrue();
    }

    @Test
    void toleratesCollapsedWhitespaceFromHtmlFormatting() {
        // Simulates messy whitespace left over from HTML stripping (multiple spaces,
        // a stray newline where a <br> or paragraph boundary was).
        String source = "We   depend  on\n\na single foundry partner for all advanced nodes.";
        String excerpt = "We depend on a single foundry partner for all advanced nodes.";
        assertThat(service.isVerbatim(excerpt, source)).isTrue();
    }

    @Test
    void toleratesLeadingTrailingWhitespaceOnTheExcerptItself() {
        String source = "Our sole supplier for this component is located in Taiwan.";
        String excerpt = "  Our sole supplier for this component is located in Taiwan.  ";
        assertThat(service.isVerbatim(excerpt, source)).isTrue();
    }

    @Test
    void rejectsAFabricatedExcerptNotPresentAtAll() {
        String source = "We rely on multiple suppliers across our operations to reduce concentration risk.";
        String fabricated = "We rely on a single supplier for all critical components with no backup.";
        assertThat(service.isVerbatim(fabricated, source)).isFalse();
    }

    @Test
    void rejectsAPlausibleParaphraseThatIsNotAnExactQuote() {
        // The model summarised/paraphrased instead of quoting — must be rejected even
        // though it is semantically close and mentions the same real fact.
        String source = "The Company depends on Taiwan Semiconductor Manufacturing Company as its sole "
                + "foundry for advanced-node chips.";
        String paraphrase = "The Company is dependent on TSMC as its only chip foundry.";
        assertThat(service.isVerbatim(paraphrase, source)).isFalse();
    }

    @Test
    void rejectsAnExcerptThatIsOnlyPartiallyPresent() {
        // First half is real, second half was invented/extended by the model —
        // the WHOLE excerpt must be found, not just a prefix of it.
        String source = "We rely on a single supplier for this component.";
        String extended = "We rely on a single supplier for this component and have no viable alternative.";
        assertThat(service.isVerbatim(extended, source)).isFalse();
    }

    @Test
    void isCaseSensitiveDeliberately() {
        // A changed capitalisation is a real (if small) signal of paraphrasing rather
        // than quotation — see the class Javadoc for why case is NOT normalised.
        String source = "Taiwan Semiconductor Manufacturing Company is our primary foundry.";
        String wrongCase = "taiwan semiconductor manufacturing company is our primary foundry.";
        assertThat(service.isVerbatim(wrongCase, source)).isFalse();
    }

    @Test
    void emptyOrBlankExcerptIsNeverConsideredVerbatim() {
        String source = "Some real filing text that is definitely non-empty.";
        assertThat(service.isVerbatim("", source)).isFalse();
        assertThat(service.isVerbatim("   ", source)).isFalse();
        assertThat(service.isVerbatim(null, source)).isFalse();
    }

    @Test
    void nullSourceTextIsNeverConsideredAMatch() {
        assertThat(service.isVerbatim("Some excerpt", null)).isFalse();
    }

    @Test
    void normalizeIsSymmetricBetweenExcerptAndSource() {
        // Both sides go through the exact same normalisation, so whichever side
        // happens to carry the curly quote / extra whitespace, the comparison still
        // succeeds. This guards against a bug where only one side was normalised.
        String sourceWithCurly = "a “sole-source” component";
        String excerptWithStraight = "a \"sole-source\" component";
        assertThat(service.isVerbatim(excerptWithStraight, sourceWithCurly)).isTrue();

        String sourceWithStraight = "a \"sole-source\" component";
        String excerptWithCurly = "a “sole-source” component";
        assertThat(service.isVerbatim(excerptWithCurly, sourceWithStraight)).isTrue();
    }
}
