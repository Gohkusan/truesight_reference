package com.truesight.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One article backing one {@link Alert}. AC 7.1: "the same story reported by multiple
 * outlets is collapsed into one alert with all links attached" — this is the "all
 * links" side of that: many AlertSource rows, one parent Alert, deduplicated by title
 * similarity at ingestion time (see {@code NewsIngestionService}).
 */
@Entity
@Table(name = "alert_source", indexes = @Index(name = "ix_alert_source_alert", columnList = "alert_id"))
public class AlertSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false)
    private Alert alert;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "outlet", length = 200)
    private String outlet;

    @Column(name = "article_url", nullable = false, length = 1000)
    private String articleUrl;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    protected AlertSource() {
        // JPA
    }

    public AlertSource(Alert alert, String title, String articleUrl, Instant publishedAt) {
        this.alert = alert;
        this.title = title;
        this.articleUrl = articleUrl;
        this.publishedAt = publishedAt;
    }

    public Long getId() {
        return id;
    }

    public Alert getAlert() {
        return alert;
    }

    public String getTitle() {
        return title;
    }

    public String getOutlet() {
        return outlet;
    }

    public void setOutlet(String outlet) {
        this.outlet = outlet;
    }

    public String getArticleUrl() {
        return articleUrl;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
