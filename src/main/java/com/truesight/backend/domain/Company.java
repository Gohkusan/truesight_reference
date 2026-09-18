package com.truesight.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One row per real-world company. This table is intentionally NOT scoped to a user or
 * portfolio: if two different users both hold NVIDIA, they must resolve to the exact
 * same Company row, or shared-supplier detection and node counts become per-user
 * accidents of upload order instead of facts about the real world. Per-user isolation
 * (AC 1.3) is enforced one layer up, at Holding/Portfolio/Relationship-visibility, not
 * by duplicating company identity per account.
 *
 * <p>{@code canonicalKey} is the output of entity normalisation (strip legal suffixes,
 * match to SEC CIK where possible) and is the thing that makes "TSMC", "Taiwan
 * Semiconductor", and "TSM" collapse to one row. See
 * {@code com.truesight.backend.ingestion.EntityNormalizationService} for the algorithm;
 * this entity only stores its output.
 */
@Entity
@Table(
        name = "company",
        uniqueConstraints = @UniqueConstraint(name = "uk_company_canonical_key", columnNames = "canonical_key"),
        indexes = {
                @Index(name = "ix_company_cik", columnList = "cik"),
                @Index(name = "ix_company_ticker", columnList = "ticker")
        }
)
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SEC's 10-digit zero-padded Central Index Key, e.g. "0000320193" for Apple. The
     * strongest identity signal available: unique, stable, assigned by a regulator.
     * Null for companies EDGAR has no record of (private suppliers, foreign entities
     * with no US filings) — see {@code isPrivate}/{@code hasNoSecFilings}.
     */
    @Column(length = 10)
    private String cik;

    /** Most recently seen exchange ticker. May be null for private companies. */
    private String ticker;

    /** Display name as most recently confirmed from SEC data or the user's own CSV. */
    @Column(nullable = false)
    private String name;

    /**
     * The normalised join key: uppercased, legal suffixes stripped, non-alphanumerics
     * removed. Two different display names that normalise to the same key ARE the same
     * company. This is what the unique constraint above actually protects; {@code name}
     * itself is allowed to vary (SEC's on-file name vs. what a supplier string in a
     * filing called them) as long as they normalise to the same key.
     */
    @Column(name = "canonical_key", nullable = false, length = 500)
    private String canonicalKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanySource source = CompanySource.SEC_INDEX;

    /**
     * True for entities like Carl Zeiss SMT GmbH or TRUMPF SE — real companies that
     * matter to the graph but have no public ticker and no SEC filings, because they
     * are privately held or foreign entities that never registered with the SEC.
     */
    @Column(name = "is_private", nullable = false)
    private boolean isPrivate = false;

    /**
     * Distinct from isPrivate: a company can be public in its home market but have
     * filed nothing with the SEC (non-US listing, no US ADR). AC 2.2 requires this
     * shown as its own coverage reason, "No SEC filings", not folded into "Failed".
     */
    @Column(name = "has_no_sec_filings", nullable = false)
    private boolean hasNoSecFilings = false;

    /** LLM-assigned sector, overridable by the user per AC 4.9. Null until analysed. */
    private String sector;

    /** True once a user has manually overridden {@code sector} (AC 4.9). */
    @Column(name = "sector_user_overridden", nullable = false)
    private boolean sectorUserOverridden = false;

    /**
     * ISO country code from the filing's stated principal place of business (AC 4.9).
     * Deliberately nullable with no default: "where it cannot be determined the node is
     * coloured unknown and is never guessed" is a direct quote from the acceptance
     * criteria, so a null here must render as "Unknown", not as some fallback country.
     */
    private String country;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Company() {
        // JPA
    }

    public Company(String name, String canonicalKey) {
        this.name = name;
        this.canonicalKey = canonicalKey;
    }

    public Long getId() {
        return id;
    }

    public String getCik() {
        return cik;
    }

    public void setCik(String cik) {
        this.cik = cik;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCanonicalKey() {
        return canonicalKey;
    }

    public void setCanonicalKey(String canonicalKey) {
        this.canonicalKey = canonicalKey;
    }

    public CompanySource getSource() {
        return source;
    }

    public void setSource(CompanySource source) {
        this.source = source;
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setPrivate(boolean aPrivate) {
        isPrivate = aPrivate;
    }

    public boolean isHasNoSecFilings() {
        return hasNoSecFilings;
    }

    public void setHasNoSecFilings(boolean hasNoSecFilings) {
        this.hasNoSecFilings = hasNoSecFilings;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public boolean isSectorUserOverridden() {
        return sectorUserOverridden;
    }

    public void setSectorUserOverridden(boolean sectorUserOverridden) {
        this.sectorUserOverridden = sectorUserOverridden;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
