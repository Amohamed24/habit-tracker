package com.example.habitflow.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class HabitResponse {
    private UUID id;
    private String name;
    private String description;
    private String targetFrequency;
    private int currentStreak;
    private int totalCompletions;
    private boolean completedToday;
    private LocalDateTime createdAt;
}