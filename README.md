# TrueSight (reference implementation)

Supply-chain risk analysis for equity portfolios. An asset manager uploads holdings;
TrueSight pulls each company's latest SEC filing, uses Gemini to extract suppliers and
customers, builds a multi-tier dependency graph, and surfaces upstream chokepoints —
shared suppliers, single-source dependencies — that a holdings table alone would never
show.

**This is a reference implementation for a CS203 team project**, built to be studied
and re-derived from, not the code the team submits. See the comments throughout the
codebase for why non-obvious decisions were made the way they were, not just what the
code does.

## Running it

Requirements: JDK 21+ (JDK 25 works fine — the project targets bytecode level 21).
No Maven, Docker, or Node install needed.

```powershell
# 1. Copy the env template and fill in your Gemini API key (get one free at
#    https://aistudio.google.com/apikey)
copy .env.example .env
# then edit .env

# 2. Run it
.\mvnw.cmd spring-boot:run
```

The app serves at **http://localhost:8085**. API docs at `/swagger-ui.html`. The H2
console (dev only) is at `/h2-console` — JDBC URL `jdbc:h2:file:./data/truesight`,
user `sa`, blank password.

Data persists to `./data/truesight.mv.db` (H2 file-based; gitignored). Delete that file
to reset to a clean database — Flyway will recreate the schema from
`src/main/resources/db/migration/` on next boot.

## Why H2, not Postgres

Docker isn't available in the build environment this was built in. The schema is
managed entirely through Flyway migrations and JPA (`ddl-auto=validate`, never
`update`/`create`), so switching to Postgres later is a config-only change: swap the
`spring.datasource.*` block in `application.yml` for a Postgres URL/driver, add the
`postgresql` JDBC driver dependency, and nothing above the JDBC layer needs to change.

## Layout — one slice per workstream

The package structure mirrors the workstreams a team would naturally divide this
project along, so each person's slice is a coherent set of packages to own rather than
scattered files:

| Workstream | Packages | Concern |
|---|---|---|
| **A.** Data & persistence | `domain/`, `repository/`, `db/migration/` | Entities, Flyway schema, repositories |
| **B.** Auth & API surface | `security/`, `web/` (controllers + DTOs) | JWT auth, ownership scoping, springdoc |
| **C.** Ingestion pipeline | `ingestion/`, `ingestion/sec/`, `ingestion/llm/`, `ingestion/news/` | CSV parsing, entity normalisation, SEC fetch, Gemini extraction, verbatim excerpt guard |
| **D.** Risk & alerts | `risk/` | Shared-supplier detection, risk scoring, news-to-alert conversion |
| **E.** Frontend | `src/main/resources/static/` | Vanilla HTML/CSS/JS, no build step |
| **F.** Process | *(not code)* | CI, branch protection — see `.github/` once added |

`common/` and `config/` hold cross-cutting pieces (exception handling, `.env` loading,
typed config properties) that every workstream depends on but none of them owns.

## The two hard parts

The brief that produced this reference is explicit that these two pieces are the ones
most worth studying — they're what makes the rest of the app trustworthy rather than
merely plausible-looking:

- **Entity normalisation** (`ingestion/EntityNormalizationService`, once written): "TSMC",
  "Taiwan Semiconductor", and "TSM" must resolve to exactly one `Company` row. Strip
  legal suffixes, match to SEC CIK where possible, key on a canonical string. Every
  downstream feature — shared-supplier detection, node sizing, risk scores — is silently
  wrong if this is wrong, because it double-counts the same real company as two nodes.

- **The verbatim excerpt guard** (`ingestion/llm/ExcerptVerificationService`, once
  written): every relationship the LLM extracts must come with a quoted excerpt, and
  that excerpt must be found by literal (whitespace/quote-normalised) substring search
  in the actual filing text that was fetched. If it isn't found, the relationship is
  discarded and logged — never shown. This is the hallucination guard; without it nothing
  in this app is distinguishable from a plausible-sounding invention.

## Secrets

The Gemini API key lives in a gitignored `.env` file (see `.env.example`), loaded at
startup by `config/DotEnvPostProcessor` before the Spring context initialises. It is
read server-side only — never accepted as a request parameter, never returned in any
response, never editable from the UI. Real environment variables (as in any deployed
environment) always take priority over `.env` if both are present.
