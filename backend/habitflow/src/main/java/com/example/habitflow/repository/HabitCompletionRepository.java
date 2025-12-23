package com.example.habitflow.repository;

import com.example.habitflow.entity.HabitCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HabitCompletionRepository extends JpaRepository<HabitCompletion, UUID> {
    List<HabitCompletion> findByHabitIdOrderByCompletedAtDesc(UUID habitId);
    Optional<HabitCompletion> findByHabitIdAndCompletedAt(UUID habitId, LocalDate completedAt);
    boolean existsByHabitIdAndCompletedAt(UUID habitId, LocalDate completedAt);
    long countByHabitId(UUID habitId);
}