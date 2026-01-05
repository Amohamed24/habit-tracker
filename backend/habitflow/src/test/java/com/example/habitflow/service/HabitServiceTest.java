package com.example.habitflow.service;

import com.example.habitflow.dto.CreateHabitRequest;
import com.example.habitflow.dto.HabitResponse;
import com.example.habitflow.dto.UpdateHabitRequest;
import com.example.habitflow.entity.Habit;
import com.example.habitflow.entity.HabitCompletion;
import com.example.habitflow.entity.User;
import com.example.habitflow.exception.BadRequestException;
import com.example.habitflow.exception.ResourceNotFoundException;
import com.example.habitflow.repository.HabitCompletionRepository;
import com.example.habitflow.repository.HabitRepository;
import com.example.habitflow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HabitServiceTest {

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private HabitCompletionRepository completionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private HabitService habitService;

    private User testUser;
    private Habit testHabit;
    private UUID userId;
    private UUID habitId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        habitId = UUID.randomUUID();

        testUser = new User();
        testUser.setId(userId);
        testUser.setEmail("test@example.com");

        testHabit = new Habit();
        testHabit.setId(habitId);
        testHabit.setName("Exercise");
        testHabit.setDescription("30 min workout");
        testHabit.setTargetFrequency(Habit.Frequency.DAILY);
        testHabit.setUser(testUser);
        testHabit.setCompletions(new ArrayList<>());
    }

    @Test
    @DisplayName("Create habit should return new habit")
    void createHabit_Success() {
        // Arrange
        CreateHabitRequest request = new CreateHabitRequest();
        request.setName("Exercise");
        request.setDescription("30 min workout");
        request.setTargetFrequency("DAILY");

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(habitRepository.save(any(Habit.class))).thenReturn(testHabit);

        // Act
        HabitResponse response = habitService.createHabit(request, userId);

        // Assert
        assertNotNull(response);
        assertEquals("Exercise", response.getName());
        assertEquals("30 min workout", response.getDescription());
        verify(habitRepository).save(any(Habit.class));
    }

    @Test
    @DisplayName("Create habit should fail without name")
    void createHabit_NoName_ThrowsException() {
        // Arrange
        CreateHabitRequest request = new CreateHabitRequest();
        request.setName("");
        request.setTargetFrequency("DAILY");

        // Act & Assert
        assertThrows(BadRequestException.class, () -> habitService.createHabit(request, userId));
    }

    @Test
    @DisplayName("Get habits should return user's habits")
    void getHabitsByUserId_Success() {
        // Arrange
        List<Habit> habits = List.of(testHabit);
        when(habitRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(habits);

        // Act
        List<HabitResponse> responses = habitService.getHabitsByUserId(userId);

        // Assert
        assertEquals(1, responses.size());
        assertEquals("Exercise", responses.get(0).getName());
    }

    @Test
    @DisplayName("Get habit by ID should return habit for owner")
    void getHabitById_Success() {
        // Arrange
        when(habitRepository.findById(habitId)).thenReturn(Optional.of(testHabit));

        // Act
        HabitResponse response = habitService.getHabitById(habitId, userId);

        // Assert
        assertNotNull(response);
        assertEquals("Exercise", response.getName());
    }

    @Test
    @DisplayName("Get habit by ID should fail for non-owner")
    void getHabitById_NotOwner_ThrowsException() {
        // Arrange
        UUID otherUserId = UUID.randomUUID();
        when(habitRepository.findById(habitId)).thenReturn(Optional.of(testHabit));

        // Act & Assert
        assertThrows(ResourceNotFoundException.class,
                () -> habitService.getHabitById(habitId, otherUserId));
    }

    @Test
    @DisplayName("Update habit should modify habit")
    void updateHabit_Success() {
        // Arrange
        UpdateHabitRequest request = new UpdateHabitRequest();
        request.setName("Morning Exercise");
        request.setDescription("45 min workout");
        request.setTargetFrequency("DAILY");

        when(habitRepository.findById(habitId)).thenReturn(Optional.of(testHabit));
        when(habitRepository.save(any(Habit.class))).thenReturn(testHabit);

        // Act
        HabitResponse response = habitService.updateHabit(habitId, request, userId);

        // Assert
        verify(habitRepository).save(any(Habit.class));
    }

    @Test
    @DisplayName("Delete habit should remove habit")
    void deleteHabit_Success() {
        // Arrange
        when(habitRepository.findById(habitId)).thenReturn(Optional.of(testHabit));

        // Act
        habitService.deleteHabit(habitId, userId);

        // Assert
        verify(habitRepository).delete(testHabit);
    }

    @Test
    @DisplayName("Mark complete should create completion record")
    void markComplete_Success() {
        // Arrange
        when(habitRepository.findById(habitId)).thenReturn(Optional.of(testHabit));
        when(completionRepository.existsByHabitIdAndCompletedAt(habitId, LocalDate.now()))
                .thenReturn(false);

        // Act
        HabitResponse response = habitService.markComplete(habitId, userId);

        // Assert
        verify(completionRepository).save(any(HabitCompletion.class));
    }

    @Test
    @DisplayName("Mark complete should fail if already completed today")
    void markComplete_AlreadyCompleted_ThrowsException() {
        // Arrange
        when(habitRepository.findById(habitId)).thenReturn(Optional.of(testHabit));
        when(completionRepository.existsByHabitIdAndCompletedAt(habitId, LocalDate.now()))
                .thenReturn(true);

        // Act & Assert
        assertThrows(BadRequestException.class,
                () -> habitService.markComplete(habitId, userId));
    }
}