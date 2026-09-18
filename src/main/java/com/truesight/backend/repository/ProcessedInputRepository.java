package com.truesight.backend.repository;

import com.truesight.backend.domain.ProcessedInput;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedInputRepository extends JpaRepository<ProcessedInput, Long> {

    Optional<ProcessedInput> findByInputKey(String inputKey);

    boolean existsByInputKey(String inputKey);
}
