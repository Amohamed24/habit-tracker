package com.habitflow.auth.service;

import com.habitflow.auth.dto.*;
import com.habitflow.auth.entity.User;
import com.habitflow.auth.event.KafkaProducer;
import com.habitflow.auth.event.UserRegisteredEvent;
import com.habitflow.auth.repository.UserRepository;
import com.habitflow.auth.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final KafkaProducer kafkaProducer;
    
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }
        
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();
        
        user = userRepository.save(user);
        
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        
        // Publish event to Kafka
        kafkaProducer.sendUserRegisteredEvent(UserRegisteredEvent.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .registeredAt(LocalDateTime.now())
                .build());
        
        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .build();
    }
    
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid password");
        }
        
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        
        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .build();
    }
    
    public UserValidationResponse validateToken(String token) {
        if (!jwtService.isTokenValid(token)) {
            return UserValidationResponse.builder()
                    .valid(false)
                    .build();
        }
        
        Long userId = jwtService.extractUserId(token);
        String email = jwtService.extractEmail(token);
        
        return UserValidationResponse.builder()
                .valid(true)
                .userId(userId)
                .email(email)
                .build();
    }
}