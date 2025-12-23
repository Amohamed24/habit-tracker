package com.example.habitflow.service;

import com.example.habitflow.entity.Habit;
import com.example.habitflow.entity.HabitCompletion;
import com.example.habitflow.repository.HabitRepository;
import com.example.habitflow.repository.HabitCompletionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HabitService {

    private final HabitRepository habitRepository;
    private final HabitCompletionRepository completionRepository;

    public List<Habit> getHabitsByUserId(UUID userId) {
        return habitRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Habit getHabitById(UUID id) {
        return habitRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Habit not found"));
    }

    public Habit createHabit(Habit habit) {
        return habitRepository.save(habit);
    }

    public void deleteHabit(UUID id) {
        habitRepository.deleteById(id);
    }

    public HabitCompletion markComplete(UUID habitId, LocalDate date) {
        // Check if already completed
        if (completionRepository.existsByHabitIdAndCompletedAt(habitId, date)) {
            throw new RuntimeException("Habit already completed for this date");
        }

        Habit habit = getHabitById(habitId);

        HabitCompletion completion = new HabitCompletion();
        completion.setHabit(habit);
        completion.setCompletedAt(date);

        return completionRepository.save(completion);
    }

    public void unmarkComplete(UUID habitId, LocalDate date) {
        HabitCompletion completion = completionRepository
                .findByHabitIdAndCompletedAt(habitId, date)
                .orElseThrow(() -> new RuntimeException("Completion not found"));

        completionRepository.delete(completion);
    }

    public int calculateStreak(UUID habitId) {
        List<HabitCompletion> completions = completionRepository
                .findByHabitIdOrderByCompletedAtDesc(habitId);

        if (completions.isEmpty()) return 0;

        int streak = 0;
        LocalDate expected = LocalDate.now();

        for (HabitCompletion completion : completions) {
            LocalDate date = completion.getCompletedAt();

            if (date.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (date.equals(expected.minusDays(1))) {
                // Handle case where today isn't completed yet
                expected = date;
                streak++;
                expected = expected.minusDays(1);
            } else {
                break;
            }
        }

        return streak;
    }
}
