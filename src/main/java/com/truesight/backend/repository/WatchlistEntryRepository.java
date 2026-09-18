package com.truesight.backend.repository;

import com.truesight.backend.domain.WatchlistEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, Long> {

    List<WatchlistEntry> findByUserId(Long userId);

    Optional<WatchlistEntry> findByUserIdAndCompanyId(Long userId, Long companyId);
}
