package com.habitflow.habits.repository;

import com.habitflow.habits.entity.HabitCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HabitCompletionRepository extends JpaRepository<HabitCompletion, Long> {
    List<HabitCompletion> findByHabitId(Long habitId);
    Optional<HabitCompletion> findByHabitIdAndCompletionDate(Long habitId, LocalDate date);
    boolean existsByHabitIdAndCompletionDate(Long habitId, LocalDate date);
    void deleteByHabitIdAndCompletionDate(Long habitId, LocalDate date);
}