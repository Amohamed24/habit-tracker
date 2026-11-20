package com.habitflow.habits.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Payload of habit.completion_undone: a completion for completionDate was removed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HabitCompletionUndoneEvent {
    private Long habitId;
    private Long userId;
    private LocalDate completionDate;
}
