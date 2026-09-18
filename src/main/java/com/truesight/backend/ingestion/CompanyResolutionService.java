package com.truesight.backend.ingestion;

import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.CompanySource;
import com.truesight.backend.ingestion.sec.SecTickerIndexEntry;
import com.truesight.backend.ingestion.sec.SecTickerIndexService;
import com.truesight.backend.repository.CompanyRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where {@link EntityNormalizationService} (string canonicalisation) and
 * {@link SecTickerIndexService} (the SEC's ticker/CIK directory) come together to
 * answer the actual question this app needs answered: "is this name/ticker string a
 * company we already have a Company row for, or a new one, and if new, what CIK does
 * it correspond to (if any)?"
 *
 * <p>Resolution order, cheapest and most reliable signal first:
 * <ol>
 *   <li><b>Exact ticker match</b> against the SEC index — a ticker is close to
 *       unambiguous (AAPL is always Apple), so this is tried first and, if it hits,
 *       is trusted outright.</li>
 *   <li><b>Canonical-key match</b> against an EXISTING Company row — if this exact
 *       normalised name has already been resolved before (by any prior holding, from
 *       any user's upload), reuse that row rather than creating a duplicate. This is
 *       the step that makes "TSM" typed today and "Taiwan Semiconductor" typed
 *       yesterday collapse to one node, PROVIDED both were, at some point, resolved
 *       through the ticker or CIK path to the same canonical company first — pure
 *       name variants that were never ticker-resolved won't automatically merge; see
 *       the class-level note below on this method's real limits.</li>
 *   <li><b>Canonical-key match against the SEC ticker index's company titles</b> — the
 *       index's own "title" field, canonicalised the same way, tried as a fallback
 *       when the raw input wasn't itself a valid ticker.</li>
 *   <li><b>Create a new Company</b>, marked private/no-SEC-filings as appropriate, if
 *       none of the above resolves — e.g. "Carl Zeiss SMT GmbH", a real supplier with
 *       no SEC presence at all.</li>
 * </ol>
 *
 * <p><b>Honest limitation, stated rather than hidden:</b> this resolver does NOT do
 * fuzzy/semantic alias matching ("TSMC" the initialism vs. "Taiwan Semiconductor
 * Manufacturing Company" the full SEC-registered name are not textually related, and no
 * string algorithm here bridges that gap). What DOES cover this case in practice: SEC's
 * own ticker index lists "TSM" as Taiwan Semiconductor's ticker, so a CSV holding typed
 * as "TSM" resolves correctly via step 1. An LLM-extracted supplier mention of "TSMC" in
 * running prose, with no ticker attached, is harder — for that, {@code IngestionService}
 * additionally asks the LLM extraction prompt to include a best-guess ticker/CIK
 * alongside any supplier name specifically so this resolver has a ticker to try first
 * rather than only a prose name. A production system would add a maintained
 * alias table (SEC itself publishes former-name history per CIK); flagged here as a
 * known, real gap rather than papered over.
 */
@Service
public class CompanyResolutionService {

    private final EntityNormalizationService normalizationService;
    private final SecTickerIndexService tickerIndexService;
    private final CompanyRepository companyRepository;

    public CompanyResolutionService(
            EntityNormalizationService normalizationService,
            SecTickerIndexService tickerIndexService,
            CompanyRepository companyRepository
    ) {
        this.normalizationService = normalizationService;
        this.tickerIndexService = tickerIndexService;
        this.companyRepository = companyRepository;
    }

    /**
     * Resolves a raw ticker string (from a CSV upload or "add by ticker") to a
     * persisted Company, creating one if this is the first time this company has been
     * seen. Returns empty only if the ticker is not found in the SEC index at all —
     * callers (AC 2.1, AC 2.3) surface that as "unrecognised" / "not found in SEC
     * index", never silently dropping the row.
     */
    @Transactional
    public Optional<Company> resolveByTicker(String rawTicker) {
        Optional<SecTickerIndexEntry> indexEntry = tickerIndexService.findByTicker(rawTicker);
        if (indexEntry.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(resolveFromIndexEntry(indexEntry.get()));
    }

    /**
     * Resolves an arbitrary name string — typically an LLM-extracted supplier or
     * customer mention with no attached ticker — to a Company, creating a new private/
     * no-ticker Company if nothing matches. Unlike resolveByTicker, this never returns
     * empty: a name that resolves to nothing in SEC still names a real company (e.g.
     * "Carl Zeiss SMT GmbH"), and the graph needs a node for it regardless of SEC
     * coverage.
     */
    @Transactional
    public Company resolveByName(String rawName) {
        String canonicalKey = normalizationService.canonicalize(rawName);

        // Step 2: have we already created a Company for this exact canonical key?
        Optional<Company> existing = companyRepository.findByCanonicalKey(canonicalKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Step 3: does this name, once canonicalised, match a ticker's raw name string
        // interpreted AS a ticker symbol (handles the case where an LLM extraction
        // returns something that IS actually a valid ticker, e.g. "TSM", even though
        // this method's contract is "name", not "ticker" — cheap to try, costs nothing
        // if it misses).
        Optional<SecTickerIndexEntry> asTicker = tickerIndexService.findByTicker(rawName);
        if (asTicker.isPresent()) {
            return resolveFromIndexEntry(asTicker.get());
        }

        // Step 4: genuinely new, unmatched entity. Create it as private/no-filings —
        // NOT a guess; simply the honest state until/unless a later refresh resolves
        // it (e.g. the SEC index itself updates, or a future alias table matches it).
        Company company = new Company(rawName.trim(), canonicalKey);
        company.setSource(CompanySource.LLM_EXTRACTED);
        company.setPrivate(true);
        company.setHasNoSecFilings(true);
        return companyRepository.save(company);
    }

    private Company resolveFromIndexEntry(SecTickerIndexEntry indexEntry) {
        // A company can have MULTIPLE valid resolution paths converge on it (e.g.
        // first seen by ticker, later re-encountered by CIK from a different lookup) —
        // check CIK first since it's the stronger identity signal, matching Company's
        // own Javadoc ("SEC's 10-digit... the strongest identity signal available").
        Optional<Company> byCik = companyRepository.findByCik(indexEntry.cik10());
        if (byCik.isPresent()) {
            return byCik.get();
        }

        String canonicalKey = normalizationService.canonicalize(indexEntry.companyName());
        Optional<Company> byKey = companyRepository.findByCanonicalKey(canonicalKey);
        if (byKey.isPresent()) {
            Company company = byKey.get();
            // We now have a CIK for a company that was previously created without one
            // (e.g. first seen only as an LLM-extracted name) — backfill it.
            if (company.getCik() == null) {
                company.setCik(indexEntry.cik10());
                company.setTicker(indexEntry.ticker());
                company.touch();
            }
            return company;
        }

        Company company = new Company(indexEntry.companyName(), canonicalKey);
        company.setCik(indexEntry.cik10());
        company.setTicker(indexEntry.ticker());
        company.setSource(CompanySource.SEC_INDEX);
        return companyRepository.save(company);
    }
}
