package com.example.habitflow.controller;

import com.example.habitflow.dto.CreateHabitRequest;
import com.example.habitflow.dto.HabitResponse;
import com.example.habitflow.dto.UpdateHabitRequest;
import com.example.habitflow.service.HabitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/habits")
@RequiredArgsConstructor
public class HabitController {

    private final HabitService habitService;

    private UUID getAuthenticatedUserId() {
        return (UUID) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }

    @GetMapping
    public List<HabitResponse> getMyHabits() {
        return habitService.getHabitsByUserId(getAuthenticatedUserId());
    }

    @GetMapping("/{id}")
    public HabitResponse getHabit(@PathVariable UUID id) {
        return habitService.getHabitById(id, getAuthenticatedUserId());
    }

    @PostMapping
    public ResponseEntity<HabitResponse> createHabit(@RequestBody CreateHabitRequest request) {
        HabitResponse response = habitService.createHabit(request, getAuthenticatedUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public HabitResponse updateHabit(@PathVariable UUID id, @RequestBody UpdateHabitRequest request) {
        return habitService.updateHabit(id, request, getAuthenticatedUserId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHabit(@PathVariable UUID id) {
        habitService.deleteHabit(id, getAuthenticatedUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete")
    public HabitResponse markComplete(@PathVariable UUID id) {
        return habitService.markComplete(id, getAuthenticatedUserId());
    }

    @DeleteMapping("/{id}/complete")
    public HabitResponse unmarkComplete(@PathVariable UUID id) {
        return habitService.unmarkComplete(id, getAuthenticatedUserId());
    }
}