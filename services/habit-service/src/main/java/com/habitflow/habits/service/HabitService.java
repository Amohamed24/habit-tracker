package com.habitflow.habits.service;

import com.habitflow.habits.dto.HabitRequest;
import com.habitflow.habits.dto.HabitResponse;
import com.habitflow.habits.entity.Habit;
import com.habitflow.habits.entity.HabitCompletion;
import com.habitflow.habits.event.HabitCompletedEvent;
import com.habitflow.habits.event.HabitCreatedEvent;
import com.habitflow.habits.event.KafkaProducer;
import com.habitflow.habits.repository.HabitCompletionRepository;
import com.habitflow.habits.repository.HabitRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HabitService {
    
    private final HabitRepository habitRepository;
    private final HabitCompletionRepository completionRepository;
    private final KafkaProducer kafkaProducer;
    
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
    
    public HabitResponse createHabit(HabitRequest request, Long userId) {
        Habit habit = Habit.builder()
                .name(request.getName())
                .description(request.getDescription())
                .frequency(request.getFrequency())
                .userId(userId)
                .build();
        
        habit = habitRepository.save(habit);
        
        // Publish event to Kafka
        kafkaProducer.sendHabitCreatedEvent(HabitCreatedEvent.builder()
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
    }
    
    @Transactional
    public HabitResponse toggleCompletion(Long id, Long userId) {
        Habit habit = habitRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Habit not found"));
        
        LocalDate today = LocalDate.now();
        boolean completedToday = completionRepository.existsByHabitIdAndCompletionDate(id, today);
        
        if (completedToday) {
            completionRepository.deleteByHabitIdAndCompletionDate(id, today);
        } else {
            HabitCompletion completion = HabitCompletion.builder()
                    .habit(habit)
                    .completionDate(today)
                    .build();
            completionRepository.save(completion);
            
            // Publish event to Kafka
            kafkaProducer.sendHabitCompletedEvent(HabitCompletedEvent.builder()
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