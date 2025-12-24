package com.example.habitflow.service;

import com.example.habitflow.dto.CreateHabitRequest;
import com.example.habitflow.dto.HabitResponse;
import com.example.habitflow.dto.UpdateHabitRequest;
import com.example.habitflow.entity.Habit;
import com.example.habitflow.entity.HabitCompletion;
import com.example.habitflow.entity.User;
import com.example.habitflow.exception.BadRequestException;
import com.example.habitflow.exception.ResourceNotFoundException;
import com.example.habitflow.repository.HabitRepository;
import com.example.habitflow.repository.HabitCompletionRepository;
import com.example.habitflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HabitService {

    private final HabitRepository habitRepository;
    private final HabitCompletionRepository completionRepository;
    private final UserRepository userRepository;

    public List<HabitResponse> getHabitsByUserId(UUID userId) {
        return habitRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public HabitResponse getHabitById(UUID habitId, UUID userId) {
        Habit habit = habitRepository.findById(habitId)
                .orElseThrow(() -> new ResourceNotFoundException("Habit not found with id: " + habitId));

        // Verify ownership
        if (!habit.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Habit not found with id: " + habitId);
        }

        return mapToResponse(habit);
    }

    public HabitResponse createHabit(CreateHabitRequest request, UUID userId) {
        // Validation
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BadRequestException("Habit name is required");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Habit habit = new Habit();
        habit.setUser(user);
        habit.setName(request.getName().trim());
        habit.setDescription(request.getDescription());

        if (request.getTargetFrequency() != null) {
            try {
                habit.setTargetFrequency(Habit.Frequency.valueOf(request.getTargetFrequency().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid frequency. Must be DAILY or WEEKLY");
            }
        }

        Habit savedHabit = habitRepository.save(habit);
        return mapToResponse(savedHabit);
    }

    public HabitResponse updateHabit(UUID habitId, UpdateHabitRequest request, UUID userId) {
        Habit habit = habitRepository.findById(habitId)
                .orElseThrow(() -> new ResourceNotFoundException("Habit not found with id: " + habitId));

        // Verify ownership
        if (!habit.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Habit not found with id: " + habitId);
        }

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            habit.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            habit.setDescription(request.getDescription());
        }
        if (request.getTargetFrequency() != null) {
            try {
                habit.setTargetFrequency(Habit.Frequency.valueOf(request.getTargetFrequency().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid frequency. Must be DAILY or WEEKLY");
            }
        }

        Habit updatedHabit = habitRepository.save(habit);
        return mapToResponse(updatedHabit);
    }

    public void deleteHabit(UUID habitId, UUID userId) {
        Habit habit = habitRepository.findById(habitId)
                .orElseThrow(() -> new ResourceNotFoundException("Habit not found with id: " + habitId));

        // Verify ownership
        if (!habit.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Habit not found with id: " + habitId);
        }

        habitRepository.delete(habit);
    }

    public HabitResponse markComplete(UUID habitId, UUID userId) {
        Habit habit = habitRepository.findById(habitId)
                .orElseThrow(() -> new ResourceNotFoundException("Habit not found with id: " + habitId));

        // Verify ownership
        if (!habit.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Habit not found with id: " + habitId);
        }

        LocalDate today = LocalDate.now();

        // Check if already completed today
        if (completionRepository.existsByHabitIdAndCompletedAt(habitId, today)) {
            throw new BadRequestException("Habit already completed today");
        }

        HabitCompletion completion = new HabitCompletion();
        completion.setHabit(habit);
        completion.setCompletedAt(today);
        completionRepository.save(completion);

        return mapToResponse(habit);
    }

    public HabitResponse unmarkComplete(UUID habitId, UUID userId) {
        Habit habit = habitRepository.findById(habitId)
                .orElseThrow(() -> new ResourceNotFoundException("Habit not found with id: " + habitId));

        // Verify ownership
        if (!habit.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Habit not found with id: " + habitId);
        }

        LocalDate today = LocalDate.now();

        HabitCompletion completion = completionRepository
                .findByHabitIdAndCompletedAt(habitId, today)
                .orElseThrow(() -> new BadRequestException("Habit not completed today"));

        completionRepository.delete(completion);

        return mapToResponse(habit);
    }

    // Helper: Calculate streak
    private int calculateStreak(UUID habitId) {
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
            } else if (date.equals(expected.minusDays(1)) && streak == 0) {
                // Today not completed yet, but yesterday was - start counting from yesterday
                expected = date;
                streak++;
                expected = expected.minusDays(1);
            } else {
                break;
            }
        }

        return streak;
    }

    // Helper: Map Entity to Response DTO
    private HabitResponse mapToResponse(Habit habit) {
        HabitResponse response = new HabitResponse();
        response.setId(habit.getId());
        response.setName(habit.getName());
        response.setDescription(habit.getDescription());
        response.setTargetFrequency(habit.getTargetFrequency().name());
        response.setCurrentStreak(calculateStreak(habit.getId()));
        response.setTotalCompletions((int) completionRepository.countByHabitId(habit.getId()));
        response.setCompletedToday(completionRepository.existsByHabitIdAndCompletedAt(habit.getId(), LocalDate.now()));
        response.setCreatedAt(habit.getCreatedAt());
        return response;
    }
}