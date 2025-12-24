package com.example.habitflow.dto;

import lombok.Data;

@Data
public class CreateHabitRequest {
    private String name;
    private String description;
    private String targetFrequency;
}
