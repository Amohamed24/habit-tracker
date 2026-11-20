package com.habitflow.habits.config;

import com.habitflow.habits.controller.HabitController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** PostgreSQL unique_violation. */
    static final String UNIQUE_VIOLATION = "23505";
    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * Integrity errors become a 409 that never carries the exception message, SQL or constraint
     * name. Matched by SQLState only: constraint names differ between fresh and baselined databases.
     * Only a unique violation raised by the toggle endpoint is a completion conflict; primary keys
     * also raise 23505, so a 23505 anywhere else gets the generic body. Takes precedence over the
     * RuntimeException catch-all because Spring picks the closest exception type.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HandlerMethod handlerMethod) {
        String sqlState = sqlState(ex);
        log.warn("Data integrity violation (SQLState {}) in {}", sqlState, handlerMethod.getShortLogMessage(), ex);

        // Any 23505 inside toggleCompletion maps here, even a theoretical outbox_events PK collision (R2 rework).
        if (UNIQUE_VIOLATION.equals(sqlState) && isToggleCompletion(handlerMethod)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "COMPLETION_CONFLICT", "completedToday", true));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "DATA_INTEGRITY_CONFLICT"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("error", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /** SQLState of the first java.sql.SQLException in the cause chain, or null if there is none. */
    static String sqlState(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isToggleCompletion(HandlerMethod handlerMethod) {
        return HabitController.class.isAssignableFrom(handlerMethod.getBeanType())
                && "toggleCompletion".equals(handlerMethod.getMethod().getName());
    }
}
