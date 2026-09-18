package com.truesight.backend.repository;

import com.truesight.backend.domain.Portfolio;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every finder here that returns a single Portfolio takes BOTH id and ownerId, and
 * returns Optional rather than throwing. This is the load-bearing pattern behind AC
 * 1.3: "requesting a portfolio... belonging to another account returns 404 (not 403,
 * to avoid leaking existence)". A service that calls findByIdAndOwnerId and maps empty
 * to 404 can never accidentally leak "this id exists, you're just not allowed to see
 * it" — that information is never in reach in the first place, because the query
 * itself only returns rows the caller owns. Every other per-user repository in this
 * app (Holding, Alert, ...) follows the same id+ownerId shape for the same reason.
 */
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {

    Optional<Portfolio> findByIdAndOwnerId(Long id, Long ownerId);

    List<Portfolio> findByOwnerIdOrderByCreatedAtAsc(Long ownerId);

    boolean existsByIdAndOwnerId(Long id, Long ownerId);
}
