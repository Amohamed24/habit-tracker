package com.habitflow.habits.controller;

import com.habitflow.habits.dto.HabitRequest;
import com.habitflow.habits.dto.HabitResponse;
import com.habitflow.habits.service.HabitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/habits")
@RequiredArgsConstructor
public class HabitController {
    
    private final HabitService habitService;
    
    @GetMapping
    public ResponseEntity<List<HabitResponse>> getHabits(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return ResponseEntity.ok(habitService.getHabitsByUserId(userId));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<HabitResponse> getHabit(
            @PathVariable Long id,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return ResponseEntity.ok(habitService.getHabitById(id, userId));
    }
    
    @PostMapping
    public ResponseEntity<HabitResponse> createHabit(
            @Valid @RequestBody HabitRequest habitRequest,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return ResponseEntity.ok(habitService.createHabit(habitRequest, userId));
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<HabitResponse> updateHabit(
            @PathVariable Long id,
            @Valid @RequestBody HabitRequest habitRequest,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return ResponseEntity.ok(habitService.updateHabit(id, habitRequest, userId));
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHabit(
            @PathVariable Long id,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        habitService.deleteHabit(id, userId);
        return ResponseEntity.noContent().build();
    }
    
    @PostMapping("/{id}/toggle")
    public ResponseEntity<HabitResponse> toggleCompletion(
            @PathVariable Long id,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return ResponseEntity.ok(habitService.toggleCompletion(id, userId));
    }
    
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Habit Service is running");
    }
}