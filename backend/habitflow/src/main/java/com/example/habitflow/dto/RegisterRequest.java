package com.example.habitflow.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String email;
    private String password;
}
