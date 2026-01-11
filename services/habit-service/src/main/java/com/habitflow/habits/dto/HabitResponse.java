package com.habitflow.habits.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HabitResponse {
    private Long id;
    private String name;
    private String description;
    private String frequency;
    private Long userId;
    private boolean completedToday;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}