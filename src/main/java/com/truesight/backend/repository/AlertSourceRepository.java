package com.truesight.backend.repository;

import com.truesight.backend.domain.AlertSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertSourceRepository extends JpaRepository<AlertSource, Long> {

    List<AlertSource> findByAlertId(Long alertId);

    /** Used to dedupe by URL when collapsing multi-outlet coverage of one story (AC 7.1). */
    Optional<AlertSource> findByArticleUrl(String articleUrl);
}
