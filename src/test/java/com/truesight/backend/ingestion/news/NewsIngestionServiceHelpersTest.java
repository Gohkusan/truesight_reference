package com.truesight.backend.ingestion.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesight.backend.domain.AlertSeverity;
import org.junit.jupiter.api.Test;

/** The documented threshold and the title-similarity dedupe, pinned so a later tweak is deliberate. */
class NewsIngestionServiceHelpersTest {

    @Test
    void severityBandsFollowTheDocumentedSentimentCuts() {
        assertThat(NewsIngestionService.severityFor(-0.9)).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(NewsIngestionService.severityFor(-0.75)).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(NewsIngestionService.severityFor(-0.6)).isEqualTo(AlertSeverity.ELEVATED);
        assertThat(NewsIngestionService.severityFor(-0.4)).isEqualTo(AlertSeverity.MODERATE);
        assertThat(NewsIngestionService.severityFor(-0.3)).isEqualTo(AlertSeverity.LOW);
    }

    @Test
    void sameStoryAcrossOutletsIsRecognised() {
        var a = NewsIngestionService.tokens("TSMC halts Tainan fab after earthquake");
        var b = NewsIngestionService.tokens("TSMC halts Tainan fab following earthquake - Reuters");
        assertThat(NewsIngestionService.jaccard(a, b)).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void differentStoriesAreNotCollapsed() {
        var a = NewsIngestionService.tokens("TSMC halts Tainan fab after earthquake");
        var b = NewsIngestionService.tokens("TSMC reports record quarterly revenue on AI demand");
        assertThat(NewsIngestionService.jaccard(a, b)).isLessThan(0.6);
    }

    @Test
    void stopwordsDoNotInflateSimilarity() {
        // Two unrelated headlines that share only function words must not look similar.
        var a = NewsIngestionService.tokens("The company said it is on track");
        var b = NewsIngestionService.tokens("The supplier said it is at risk");
        assertThat(NewsIngestionService.jaccard(a, b)).isLessThan(0.6);
    }
}
