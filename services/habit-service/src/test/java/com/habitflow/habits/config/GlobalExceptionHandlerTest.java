package com.habitflow.habits.config;

import com.habitflow.habits.client.AuthServiceClient;
import com.habitflow.habits.controller.HabitController;
import com.habitflow.habits.dto.UserValidationResponse;
import com.habitflow.habits.service.HabitService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R1-08 / HF-14: integrity errors map to a 409 by SQLState, with no SQL text in the body. */
@WebMvcTest(HabitController.class)
class GlobalExceptionHandlerTest {

    private static final String LEAKY_SQL = "insert into habit_completions (completion_date,created_at,habit_id)"
            + " values (?,?,?)";

    @Autowired MockMvc mockMvc;
    @MockBean HabitService habitService;
    @MockBean AuthServiceClient authServiceClient;

    @BeforeEach
    void authenticate() {
        when(authServiceClient.validateToken(anyString()))
                .thenReturn(UserValidationResponse.builder().valid(true).userId(7L).build());
    }

    @Test
    void uniqueViolationOnToggle_returns409CompletionConflict_withoutSqlText() throws Exception {
        when(habitService.toggleCompletion(anyLong(), anyLong()))
                .thenThrow(integrityViolation("23505", "uk_habit_completions_habit_date"));

        MvcResult result = mockMvc.perform(post("/api/habits/1/toggle").header("Authorization", "Bearer x"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"COMPLETION_CONFLICT\",\"completedToday\":true}", true))
                .andReturn();

        assertNoSqlLeak(result);
    }

    @Test
    void fkViolation_returnsGenericConflict_notCompletionConflict() throws Exception {
        when(habitService.toggleCompletion(anyLong(), anyLong()))
                .thenThrow(integrityViolation("23503", "fk_habit_completions_habit"));

        MvcResult result = mockMvc.perform(post("/api/habits/1/toggle").header("Authorization", "Bearer x"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"DATA_INTEGRITY_CONFLICT\"}", true))
                .andReturn();

        assertNoSqlLeak(result);
    }

    @Test
    void uniqueViolationOutsideToggle_returnsGenericConflict() throws Exception {
        // e.g. a primary-key collision on habits: also 23505, but not a completion conflict.
        when(habitService.createHabit(any(), anyLong())).thenThrow(integrityViolation("23505", "habits_pkey"));

        MvcResult result = mockMvc.perform(post("/api/habits").header("Authorization", "Bearer x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Read\",\"frequency\":\"DAILY\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"DATA_INTEGRITY_CONFLICT\"}", true))
                .andReturn();

        assertNoSqlLeak(result);
    }

    @Test
    void integrityViolationWithoutSqlState_returnsGenericConflict() throws Exception {
        when(habitService.toggleCompletion(anyLong(), anyLong()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement; SQL [" + LEAKY_SQL + "]"));

        MvcResult result = mockMvc.perform(post("/api/habits/1/toggle").header("Authorization", "Bearer x"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"DATA_INTEGRITY_CONFLICT\"}", true))
                .andReturn();

        assertNoSqlLeak(result);
    }

    @Test
    void otherRuntimeException_stillReturns400() throws Exception {
        // The catch-all is out of scope (R2); it must keep working unchanged.
        when(habitService.toggleCompletion(anyLong(), anyLong())).thenThrow(new RuntimeException("Habit not found"));

        mockMvc.perform(post("/api/habits/1/toggle").header("Authorization", "Bearer x"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Habit not found\"}", true));
    }

    /** The shape Spring Data really throws: DIVE -> Hibernate ConstraintViolationException -> PSQLException. */
    private static DataIntegrityViolationException integrityViolation(String sqlState, String constraint) {
        String pgMessage = "ERROR: violates constraint \"" + constraint + "\"";
        SQLException jdbc = new SQLException(pgMessage, sqlState);
        ConstraintViolationException hibernate = new ConstraintViolationException(
                "could not execute statement [" + pgMessage + "] [" + LEAKY_SQL + "]", jdbc, LEAKY_SQL, constraint);
        return new DataIntegrityViolationException("could not execute statement [" + pgMessage + "] [" + LEAKY_SQL
                + "]; SQL [" + LEAKY_SQL + "]; constraint [" + constraint + "]", hibernate);
    }

    private static void assertNoSqlLeak(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString())
                .doesNotContainIgnoringCase("insert")
                .doesNotContainIgnoringCase("sql")
                .doesNotContainIgnoringCase("constraint")
                .doesNotContainIgnoringCase("violates")
                .doesNotContain("habit_completions")
                .doesNotContain("uk_")
                .doesNotContain("fk_")
                .doesNotContain("pkey");
    }
}
