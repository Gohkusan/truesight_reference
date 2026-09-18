-- V1: initial schema for TrueSight.
--
-- Table order follows foreign-key dependency (referenced tables first). Column types
-- and nullability mirror the JPA entities in com.truesight.backend.domain exactly —
-- Hibernate is configured with ddl-auto=validate (see application.yml), so this file,
-- not the entities, is the single source of truth for the schema; a mismatch here
-- fails application startup loudly instead of silently drifting.

-- ============================================================================
-- Workstream A core: accounts and portfolios
-- ============================================================================

CREATE TABLE app_user (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    email               VARCHAR(255) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    display_name        VARCHAR(255),
    firm                VARCHAR(255),
    alert_email         VARCHAR(255),
    created_at          TIMESTAMP NOT NULL,
    previous_login_at   TIMESTAMP,
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

CREATE TABLE user_settings (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                 BIGINT NOT NULL,
    minimum_alert_severity  VARCHAR(20) NOT NULL,
    event_types             VARCHAR(500),
    CONSTRAINT uk_user_settings_user UNIQUE (user_id),
    CONSTRAINT fk_user_settings_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE TABLE portfolio (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    name                VARCHAR(255) NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    last_analysed_at    TIMESTAMP,
    CONSTRAINT fk_portfolio_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
CREATE INDEX ix_portfolio_user ON portfolio (user_id);

-- ============================================================================
-- Shared entity graph: companies and their relationships. NOT user-scoped —
-- see Company.java / Relationship.java Javadoc for why.
-- ============================================================================

CREATE TABLE company (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    cik                         VARCHAR(10),
    ticker                      VARCHAR(20),
    name                        VARCHAR(500) NOT NULL,
    canonical_key               VARCHAR(500) NOT NULL,
    source                      VARCHAR(20) NOT NULL,
    is_private                  BOOLEAN NOT NULL DEFAULT FALSE,
    has_no_sec_filings          BOOLEAN NOT NULL DEFAULT FALSE,
    sector                      VARCHAR(200),
    sector_user_overridden      BOOLEAN NOT NULL DEFAULT FALSE,
    country                     VARCHAR(10),
    created_at                  TIMESTAMP NOT NULL,
    updated_at                  TIMESTAMP NOT NULL,
    CONSTRAINT uk_company_canonical_key UNIQUE (canonical_key)
);
CREATE INDEX ix_company_cik ON company (cik);
CREATE INDEX ix_company_ticker ON company (ticker);

CREATE TABLE holding (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id        BIGINT NOT NULL,
    company_id          BIGINT NOT NULL,
    weight_percent      DECIMAL(9,4),
    shares              DECIMAL(20,4),
    market_value        DECIMAL(20,2),
    coverage_status     VARCHAR(20) NOT NULL,
    failure_reason      VARCHAR(500),
    last_analysed_at    TIMESTAMP,
    is_newly_added      BOOLEAN NOT NULL DEFAULT FALSE,
    is_removed          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT fk_holding_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolio (id),
    CONSTRAINT fk_holding_company FOREIGN KEY (company_id) REFERENCES company (id)
);
CREATE INDEX ix_holding_portfolio ON holding (portfolio_id);

CREATE TABLE watchlist_entry (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    company_id  BIGINT NOT NULL,
    added_at    TIMESTAMP NOT NULL,
    CONSTRAINT uk_watchlist_user_company UNIQUE (user_id, company_id),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_watchlist_company FOREIGN KEY (company_id) REFERENCES company (id)
);
CREATE INDEX ix_watchlist_user ON watchlist_entry (user_id);

CREATE TABLE relationship (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_company_id     BIGINT NOT NULL,
    to_company_id       BIGINT NOT NULL,
    relationship_type   VARCHAR(20) NOT NULL,
    criticality         VARCHAR(20) NOT NULL,
    dependency_percent  INT,
    disruption_reason   VARCHAR(2000),
    tier                INT NOT NULL,
    confidence_score    INT,
    sources_conflict    BOOLEAN NOT NULL DEFAULT FALSE,
    no_longer_disclosed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT uk_relationship_edge UNIQUE (from_company_id, to_company_id, relationship_type),
    CONSTRAINT fk_relationship_from FOREIGN KEY (from_company_id) REFERENCES company (id),
    CONSTRAINT fk_relationship_to FOREIGN KEY (to_company_id) REFERENCES company (id)
);
CREATE INDEX ix_relationship_from ON relationship (from_company_id);
CREATE INDEX ix_relationship_to ON relationship (to_company_id);

CREATE TABLE evidence (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    relationship_id     BIGINT NOT NULL,
    source_type         VARCHAR(20) NOT NULL,
    excerpt             CLOB NOT NULL,
    source_date         DATE,
    source_url          VARCHAR(1000),
    accession_number    VARCHAR(30),
    CONSTRAINT fk_evidence_relationship FOREIGN KEY (relationship_id) REFERENCES relationship (id)
);
CREATE INDEX ix_evidence_relationship ON evidence (relationship_id);

CREATE TABLE relationship_review (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    relationship_id     BIGINT NOT NULL,
    status              VARCHAR(20) NOT NULL,
    note                VARCHAR(1000),
    reviewed_at         TIMESTAMP NOT NULL,
    CONSTRAINT uk_review_user_relationship UNIQUE (user_id, relationship_id),
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_review_relationship FOREIGN KEY (relationship_id) REFERENCES relationship (id)
);
CREATE INDEX ix_review_relationship ON relationship_review (relationship_id);

-- ============================================================================
-- Risk scoring (append-only history — see RiskAssessment.java Javadoc)
-- ============================================================================

CREATE TABLE risk_assessment (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    holding_id          BIGINT,
    relationship_id     BIGINT,
    score               INT,
    severity            VARCHAR(20) NOT NULL,
    factors_json        CLOB,
    change_reason       VARCHAR(1000),
    assessed_at         TIMESTAMP NOT NULL,
    CONSTRAINT fk_risk_holding FOREIGN KEY (holding_id) REFERENCES holding (id),
    CONSTRAINT fk_risk_relationship FOREIGN KEY (relationship_id) REFERENCES relationship (id)
);
CREATE INDEX ix_risk_holding ON risk_assessment (holding_id, assessed_at);
CREATE INDEX ix_risk_relationship ON risk_assessment (relationship_id, assessed_at);

-- ============================================================================
-- Alerts (Epic 7)
-- ============================================================================

CREATE TABLE alert (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    portfolio_id        BIGINT NOT NULL,
    source_company_id   BIGINT NOT NULL,
    headline            VARCHAR(500) NOT NULL,
    summary             VARCHAR(4000),
    severity            VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    dismiss_reason      VARCHAR(20),
    event_at            TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT fk_alert_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolio (id),
    CONSTRAINT fk_alert_company FOREIGN KEY (source_company_id) REFERENCES company (id)
);
CREATE INDEX ix_alert_portfolio ON alert (portfolio_id, status);

CREATE TABLE alert_source (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id            BIGINT NOT NULL,
    title               VARCHAR(500) NOT NULL,
    outlet              VARCHAR(200),
    article_url         VARCHAR(1000) NOT NULL,
    published_at        TIMESTAMP NOT NULL,
    CONSTRAINT fk_alert_source_alert FOREIGN KEY (alert_id) REFERENCES alert (id)
);
CREATE INDEX ix_alert_source_alert ON alert_source (alert_id);

-- ============================================================================
-- AI reliability (Epic 9) and adaptive refresh (Epic 8)
-- ============================================================================

CREATE TABLE audit_log (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    action_type         VARCHAR(40) NOT NULL,
    target_entity       VARCHAR(200) NOT NULL,
    model_name          VARCHAR(100) NOT NULL,
    input_reference     CLOB,
    output_summary      CLOB,
    latency_ms          BIGINT,
    success             BOOLEAN NOT NULL,
    error_message       VARCHAR(2000),
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
CREATE INDEX ix_audit_user_time ON audit_log (user_id, created_at);

CREATE TABLE processed_input (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    type                    VARCHAR(20) NOT NULL,
    input_key               VARCHAR(1000) NOT NULL,
    relationships_found     INT,
    processed_at            TIMESTAMP NOT NULL,
    CONSTRAINT uk_processed_input_key UNIQUE (input_key)
);
