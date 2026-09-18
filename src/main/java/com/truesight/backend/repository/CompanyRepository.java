package com.truesight.backend.repository;

import com.truesight.backend.domain.Company;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Not scoped to a user — see Company.java Javadoc. findByCanonicalKey is the lookup
 * entity normalisation resolves everything through: given a raw name string, the
 * normalisation service computes its canonical key first, THEN calls this, so "does
 * this company already exist" is always a single indexed equality lookup rather than
 * fuzzy matching at query time.
 */
public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByCanonicalKey(String canonicalKey);

    Optional<Company> findByCik(String cik);

    Optional<Company> findByTickerIgnoreCase(String ticker);
}
