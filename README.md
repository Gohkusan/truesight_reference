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

The app serves at **http://localhost:8085**. Open that in a browser, register an
account, and upload a CSV (the columns can be in any order: Ticker/Symbol, Name,
Weight, Shares, MarketValue). API docs at `/swagger-ui.html`. The H2 console (dev
only) is at `/h2-console` — JDBC URL `jdbc:h2:file:./data/truesight`, user `sa`,
blank password.

Data persists to `./data/truesight.mv.db` (H2 file-based; gitignored). Delete that file
to reset to a clean database — Flyway will recreate the schema from
`src/main/resources/db/migration/` on next boot.

A minimal CSV to try:

```csv
Ticker,Weight
AAPL,40
NVDA,35
TSM,25
```

### What you should see

Upload shows a preview first: parsed rows, any duplicate tickers merged with a
warning, non-equity rows skipped with a note, and tickers not in the SEC index
flagged as unrecognised (excluded by default, never silently dropped). Nothing is
written until you confirm. Confirming starts analysis with a progress bar you can
cancel; holdings already analysed stay usable.

Analysis fetches each holding's primary SEC filing (annual report preferred over a
quarterly or an 8-K) and asks Gemini to extract suppliers and customers. **Every
extracted relationship must arrive with a quoted excerpt that is then found
verbatim in the filing text we actually fetched; anything that fails is discarded
and logged.** So a holding can legitimately end up analysed with zero
relationships — that shows as Unknown, never as Low.

If the Gemini free tier is rate-limited or exhausted, holdings show Failed with the
specific reason, a banner names it, and previously analysed results stay visible.
That is the designed behaviour, not a crash.

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

- **Entity normalisation** (`ingestion/EntityNormalizationService` +
  `ingestion/CompanyResolutionService`): "TSMC", "Taiwan Semiconductor", and "TSM" must
  resolve to exactly one `Company` row. Every downstream feature — shared-supplier
  detection, node sizing, risk scores — is silently wrong otherwise, because it
  double-counts one real company as two nodes.

  The two halves are deliberately separate. `EntityNormalizationService` is a pure
  function (uppercase, fold punctuation, strip legal suffix tokens) with no I/O, so it
  can be tested exhaustively; `CompanyResolutionService` does the lookups (ticker →
  CIK → existing canonical key). It also states its own limit rather than hiding it:
  no string transform turns the initialism "TSMC" into "Taiwan Semiconductor", so that
  case is bridged by ticker/CIK resolution, not by canonicalisation.

  Two real bugs here were caught by tests, not by reading: suffix stripping ran
  *before* punctuation normalisation, so "ASML Holding N.V." matched no suffix at all;
  and once periods became spaces, both the joined `NV` and spaced `N V` forms were
  needed. See `EntityNormalizationServiceTest`.

- **The verbatim excerpt guard** (`ingestion/llm/ExcerptVerificationService`): every
  relationship the LLM extracts must come with a quoted excerpt, and that excerpt must
  be found by literal substring search in the filing text actually fetched. Not found →
  the relationship is discarded and logged, never shown.

  Normalisation is deliberately narrow — whitespace runs and quote-character variants
  only, and it is case-sensitive. Wider matching would forgive paraphrase, which is
  exactly what the guard exists to catch; narrower matching would throw away true
  excerpts over a curly apostrophe.

  `GeminiExtractionServiceTest` is the proof it is load-bearing: a mocked model
  response containing one genuine excerpt and one fabricated one, asserting that only
  the genuine relationship is persisted and that the fabricated counterparty is never
  even resolved to a `Company`.

## Decisions worth knowing before you rebuild this

- **Company and Relationship are shared, not per-user.** Two analysts holding the same
  stock must see the same supplier graph; a dependency is a fact about the world.
  What *is* per-user is judgement: `RelationshipReview` holds one analyst's
  confirm/reject so it can never hide an edge from another's graph. Per-user isolation
  is enforced at Portfolio/Holding/Alert instead.
- **RiskAssessment is append-only.** A new row is written only when the score or
  factors actually change. "Current" is the latest row, "history" is all of them, so
  trend (AC 6.4) and history (AC 8.3) fall out of one table with no snapshot job.
- **Every stored edge points supplier → buyer.** "Customer" is a read-time notion
  relative to the selected node, so graph traversal is one query in one direction.
- **Transaction isolation was earned, not assumed.** `extractAndPersist` and the
  audit-log write use `REQUIRES_NEW`. Without it, one holding's failure marked the
  shared transaction rollback-only and silently discarded every *successful* holding
  in the same batch at commit — found by running the app, not by reading it.
- **Nothing is fabricated to fill a gap.** Missing CSV weights stay null rather than
  defaulting; an unscoreable holding is `UNKNOWN`, never `LOW`; a country that cannot
  be determined is "Unknown", never guessed. The old prototype's hardcoded timeline was
  cut entirely for the same reason.

## Testing

```powershell
.\mvnw.cmd test
```

66 tests. The ones worth reading first:

| Test | What it proves |
|---|---|
| `ExcerptVerificationServiceTest` | The guard forgives formatting but rejects paraphrase, partial matches and fabrication |
| `GeminiExtractionServiceTest` | A fabricated excerpt cannot become a database row; retry policy is per failure kind |
| `EntityNormalizationServiceTest` | One company = one key across suffix, punctuation and case variants |
| `RiskScoringIntegrationTest` | Node size, the shared-supplier table and the risk factor report the same count; rejecting an edge changes all three and records why |
| `CrossAccountIsolationTest` | Account B gets 404 (not 403) on account A's portfolio — the test AC 1.3 asks for by name |
| `PortfolioCsvParserTest` | Every AC 2.1 clause: aliases, duplicates, non-equity, no defaulted weights |

## Secrets

The Gemini API key lives in a gitignored `.env` file (see `.env.example`), loaded at
startup by `config/DotEnvPostProcessor` before the Spring context initialises. It is
read server-side only — never accepted as a request parameter, never returned in any
response, never editable from the UI. Real environment variables (as in any deployed
environment) always take priority over `.env` if both are present.
