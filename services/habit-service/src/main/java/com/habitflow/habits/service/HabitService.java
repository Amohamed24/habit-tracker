package com.habitflow.habits.service;

import com.habitflow.habits.dto.HabitRequest;
import com.habitflow.habits.dto.HabitResponse;
import com.habitflow.habits.entity.Habit;
import com.habitflow.habits.entity.HabitCompletion;
import com.habitflow.habits.event.HabitCompletedEvent;
import com.habitflow.habits.event.HabitCompletionUndoneEvent;
import com.habitflow.habits.event.HabitCreatedEvent;
import com.habitflow.habits.outbox.OutboxWriter;
import com.habitflow.habits.repository.HabitCompletionRepository;
import com.habitflow.habits.repository.HabitRepository;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HabitService {

    static final String AGGREGATE_HABIT = "habit";
    static final String HABIT_CREATED = "habit.created";
    static final String HABIT_COMPLETED = "habit.completed";
    static final String HABIT_COMPLETION_UNDONE = "habit.completion_undone";
    
    private final HabitRepository habitRepository;
    private final HabitCompletionRepository completionRepository;
    private final OutboxWriter outboxWriter;
    private final Counter habitsCreatedCounter;
    private final Counter habitsCompletedCounter;
    private final Counter habitsDeletedCounter;
    
    public List<HabitResponse> getHabitsByUserId(Long userId) {
        return habitRepository.findByUserId(userId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    public HabitResponse getHabitById(Long id, Long userId) {
        Habit habit = habitRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Habit not found"));
        return mapToResponse(habit);
    }
    
    @Transactional
    public HabitResponse createHabit(HabitRequest request, Long userId) {
        Habit habit = Habit.builder()
                .name(request.getName())
                .description(request.getDescription())
                .frequency(request.getFrequency())
                .userId(userId)
                .build();

        // IDENTITY id: the INSERT runs here, so the id is known before the outbox write.
        habit = habitRepository.save(habit);

        // Increment counter
        habitsCreatedCounter.increment();

        // Same transaction as the habit row: both commit or neither does (no Kafka call here).
        outboxWriter.write(AGGREGATE_HABIT, habit.getId().toString(), HABIT_CREATED,
                HabitCreatedEvent.builder()
                        .habitId(habit.getId())
                        .userId(userId)
                        .habitName(habit.getName())
                        .frequency(habit.getFrequency())
                        .createdAt(LocalDateTime.now())
                        .build());

        return mapToResponse(habit);
    }
    
    public HabitResponse updateHabit(Long id, HabitRequest request, Long userId) {
        Habit habit = habitRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Habit not found"));
        
        habit.setName(request.getName());
        habit.setDescription(request.getDescription());
        habit.setFrequency(request.getFrequency());
        
        habit = habitRepository.save(habit);
        return mapToResponse(habit);
    }
    
    public void deleteHabit(Long id, Long userId) {
        Habit habit = habitRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Habit not found"));
        habitRepository.delete(habit);
        
        // Increment counter
        habitsDeletedCounter.increment();
    }
    
    @Transactional
    public HabitResponse toggleCompletion(Long id, Long userId) {
        Habit habit = habitRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Habit not found"));
        
        LocalDate today = LocalDate.now();
        boolean completedToday = completionRepository.existsByHabitIdAndCompletionDate(id, today);
        
        if (completedToday) {
            int deleted = completionRepository.deleteByHabitIdAndCompletionDate(id, today);
            // Only an actual removal is an event; 0 means another request already undid it.
            if (deleted == 1) {
                outboxWriter.write(AGGREGATE_HABIT, habit.getId().toString(), HABIT_COMPLETION_UNDONE,
                        HabitCompletionUndoneEvent.builder()
                                .habitId(habit.getId())
                                .userId(userId)
                                .completionDate(today)
                                .build());
            }
        } else {
            HabitCompletion completion = HabitCompletion.builder()
                    .habit(habit)
                    .completionDate(today)
                    .build();
            // IDENTITY id: the INSERT runs here, not at commit, so a concurrent duplicate fails right
            // here with SQLState 23505 (DataIntegrityViolationException), before the counter and outbox.
            completionRepository.save(completion);

            // Increment counter
            habitsCompletedCounter.increment();
            
            // Same transaction as the completion row: both commit or neither does (no Kafka call here).
            outboxWriter.write(AGGREGATE_HABIT, habit.getId().toString(), HABIT_COMPLETED,
                    HabitCompletedEvent.builder()
                            .habitId(habit.getId())
                            .userId(userId)
                            .habitName(habit.getName())
                            .completionDate(today)
                            .completedAt(LocalDateTime.now())
                            .build());
        }
        
        return mapToResponse(habit);
    }
    
    private HabitResponse mapToResponse(Habit habit) {
        boolean completedToday = completionRepository
                .existsByHabitIdAndCompletionDate(habit.getId(), LocalDate.now());
        
        return HabitResponse.builder()
                .id(habit.getId())
                .name(habit.getName())
                .description(habit.getDescription())
                .frequency(habit.getFrequency())
                .userId(habit.getUserId())
                .completedToday(completedToday)
                .createdAt(habit.getCreatedAt())
                .updatedAt(habit.getUpdatedAt())
                .build();
    }
}