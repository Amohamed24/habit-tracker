package com.example.habitflow.dto;

import lombok.Data;

@Data
public class UpdateHabitRequest {
    private String name;
    private String description;
    private String targetFrequency;
}

