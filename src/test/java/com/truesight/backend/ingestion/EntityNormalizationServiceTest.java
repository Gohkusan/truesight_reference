package com.truesight.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Exercises the string-canonicalisation half of entity normalisation in isolation —
 * see EntityNormalizationService's class Javadoc for why this is split from CIK/ticker
 * resolution and what each half is responsible for proving.
 *
 * <p>Note what this test does NOT claim: "TSMC" and "Taiwan Semiconductor" do NOT
 * canonicalise to the same key here — no string transform bridges an initialism to its
 * expansion. That collapse happens one layer up, in CompanyResolutionService, via SEC
 * ticker index lookup. This test proves the narrower, unglamorous thing this class
 * actually does: the same real name, written with different legal suffixes,
 * punctuation, or casing, always produces the same key.
 */
class EntityNormalizationServiceTest {

    private final EntityNormalizationService service = new EntityNormalizationService();

    @Test
    void stripsCommonLegalSuffixes() {
        assertThat(service.canonicalize("Apple Inc.")).isEqualTo(service.canonicalize("Apple Inc"));
        assertThat(service.canonicalize("Apple Inc.")).isEqualTo(service.canonicalize("Apple"));
        assertThat(service.canonicalize("Alphabet Inc.")).isEqualTo("ALPHABET");
    }

    @Test
    void stripsMultipleStackedSuffixes() {
        // "Foo Holdings Corp" — two suffix words, both must go.
        assertThat(service.canonicalize("Foo Holdings Corp")).isEqualTo("FOO");
        assertThat(service.canonicalize("Foo Holdings Corp")).isEqualTo(service.canonicalize("Foo"));
    }

    @Test
    void isCaseInsensitive() {
        assertThat(service.canonicalize("TESLA INC")).isEqualTo(service.canonicalize("tesla inc"));
        assertThat(service.canonicalize("Tesla Inc")).isEqualTo(service.canonicalize("TeSlA iNc"));
    }

    @Test
    void collapsesPunctuationAndInternalWhitespace() {
        assertThat(service.canonicalize("AT&T Inc.")).isEqualTo(service.canonicalize("AT & T Inc"));
        assertThat(service.canonicalize("Taiwan   Semiconductor")).isEqualTo(service.canonicalize("Taiwan Semiconductor"));
    }

    @Test
    void doesNotStripASuffixThatIsPartOfALongerWord() {
        // "Sysco" ends in the letters "CO" but is not "Sys" + the legal-entity word
        // "Co" — stripping it would wrongly collapse Sysco with any name ending "Co".
        assertThat(service.canonicalize("Sysco Corporation")).isEqualTo("SYSCO");
        assertThat(service.canonicalize("Sysco Corporation")).isNotEqualTo(service.canonicalize("Sys"));
    }

    @Test
    void handlesInternationalSuffixes() {
        assertThat(service.canonicalize("Carl Zeiss SMT GmbH")).isEqualTo("CARL ZEISS SMT");
        assertThat(service.canonicalize("ASML Holding N.V.")).isEqualTo("ASML");
        assertThat(service.canonicalize("TRUMPF SE")).isEqualTo("TRUMPF");
    }

    @Test
    void blankInputProducesEmptyKeyNotNull() {
        assertThat(service.canonicalize("")).isEqualTo("");
        assertThat(service.canonicalize("   ")).isEqualTo("");
        assertThat(service.canonicalize(null)).isEqualTo("");
    }

    @Test
    void differentRealCompaniesProduceDifferentKeys() {
        // A normaliser that over-strips could accidentally collapse unrelated
        // companies. Guard against that failure mode explicitly.
        assertThat(service.canonicalize("Apple Inc")).isNotEqualTo(service.canonicalize("Alphabet Inc"));
        assertThat(service.canonicalize("Microsoft Corp")).isNotEqualTo(service.canonicalize("Micro Corp"));
    }
}
