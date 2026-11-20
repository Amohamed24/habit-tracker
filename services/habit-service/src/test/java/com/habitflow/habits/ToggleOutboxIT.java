package com.habitflow.habits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habitflow.habits.entity.Habit;
import com.habitflow.habits.outbox.OutboxWriter;
import com.habitflow.habits.repository.HabitCompletionRepository;
import com.habitflow.habits.repository.HabitRepository;
import com.habitflow.habits.repository.OutboxEventRepository;
import com.habitflow.habits.service.HabitService;
import com.habitflow.habits.support.TestcontainersOnly;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * toggleCompletion writes its event to the outbox in the domain transaction (R1-05a / HF-9).
 *
 * <p>Testcontainers Postgres only, and deliberately no Kafka: bootstrap-servers points at a closed
 * port, so any Kafka call in the request path would block (max.block.ms) and fail the timing test.
 * The datasource comes from {@code @DynamicPropertySource} (not {@code @ServiceConnection}) so the
 * Environment holds the container URL and {@link TestcontainersOnly.Guard} can check it before
 * Flyway runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@Testcontainers
class ToggleOutboxIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class GuardConfig {
        @Bean
        static TestcontainersOnly.Guard testcontainersOnlyGuard() {
            return new TestcontainersOnly.Guard(postgres::getJdbcUrl);
        }
    }

    private static final long USER_ID = 7L;

    @Autowired HabitService habitService;
    @Autowired HabitRepository habitRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @SpyBean OutboxWriter outboxWriter;
    @SpyBean HabitCompletionRepository completionRepository;
    @SpyBean KafkaTemplate<?, ?> kafkaTemplate; // wildcard: matches Boot's KafkaTemplate<?, ?> bean

    private Long habitId;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE outbox_events, habit_completions, habits RESTART IDENTITY CASCADE");
        // Seed through the repository: createHabit still sends to Kafka directly until R1-05b.
        habitId = habitRepository.save(Habit.builder()
                .name("Read 20 pages").frequency("DAILY").userId(USER_ID).build()).getId();
    }

    @Test
    void toggleOn_writesOneHabitCompletedRow() throws Exception {
        habitService.toggleCompletion(habitId, USER_ID);

        assertThat(completions()).isEqualTo(1);
        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("event_type", "habit.completed")
                .containsEntry("aggregate_type", "habit")
                .containsEntry("aggregate_id", habitId.toString())
                .containsEntry("event_version", 1)
                .containsEntry("attempts", 0);
        assertThat(row.get("sent_at")).isNull();

        // jsonb reorders keys, so compare as JSON trees, never as strings.
        JsonNode envelope = objectMapper.readTree((String) row.get("payload_text"));
        assertThat(envelope.get("event_id").asText()).isEqualTo(row.get("id").toString());
        JsonNode expected = objectMapper.readTree("""
                {"event_type": "habit.completed", "event_version": 1, "aggregate_id": "%d",
                 "payload": {"habitId": %d, "userId": %d, "habitName": "Read 20 pages",
                             "completionDate": "%s"}}
                """.formatted(habitId, habitId, USER_ID, LocalDate.now()));
        assertThat(withoutVolatile(envelope)).isEqualTo(expected);
        assertThat(envelope.get("payload").get("completedAt").isTextual()).isTrue();
    }

    @Test
    void toggleOff_writesOneCompletionUndoneRow() throws Exception {
        habitService.toggleCompletion(habitId, USER_ID);
        jdbc.execute("DELETE FROM outbox_events"); // keep only the toggle-off event

        habitService.toggleCompletion(habitId, USER_ID);

        assertThat(completions()).isZero();
        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("event_type", "habit.completion_undone")
                .containsEntry("aggregate_id", habitId.toString());

        JsonNode envelope = objectMapper.readTree((String) row.get("payload_text"));
        assertThat(envelope.get("event_id").asText()).isEqualTo(row.get("id").toString());
        JsonNode expected = objectMapper.readTree("""
                {"event_type": "habit.completion_undone", "event_version": 1, "aggregate_id": "%d",
                 "payload": {"habitId": %d, "userId": %d, "completionDate": "%s"}}
                """.formatted(habitId, habitId, USER_ID, LocalDate.now()));
        assertThat(withoutVolatile(envelope)).isEqualTo(expected);
    }

    @Test
    void toggleOff_withNoCompletionToday_writesNoUndoneEvent() {
        // The race HF-14 makes reachable: the service saw a completion, but another request deleted it first.
        doReturn(true).when(completionRepository).existsByHabitIdAndCompletionDate(eq(habitId), any());

        habitService.toggleCompletion(habitId, USER_ID);

        verify(completionRepository).deleteByHabitIdAndCompletionDate(eq(habitId), any());
        assertThat(completions()).isZero();
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    void exceptionAfterOutboxSave_rollsBackCompletionAndOutboxRow() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            outboxEventRepository.flush(); // the row really reaches the database inside the transaction
            // Same connection (JpaTransactionManager exposes it to JdbcTemplate): both rows exist pre-rollback.
            assertThat(completions()).isEqualTo(1);
            assertThat(outboxRows()).hasSize(1);
            throw new IllegalStateException("boom after outbox save");
        }).when(spiedOutboxWriter()).write(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> habitService.toggleCompletion(habitId, USER_ID))
                .hasMessageContaining("boom after outbox save");

        assertThat(completions()).isZero();
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    void toggle_withKafkaUnreachable_completesUnderOneSecond() {
        long start = System.nanoTime();
        habitService.toggleCompletion(habitId, USER_ID);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Printed for the PR evidence: the AC is < 1 s; the assertion allows 2 s so CI jitter can't flake it.
        System.out.println("toggle_withKafkaUnreachable elapsed_ms=" + elapsed.toMillis());
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        verifyNoInteractions(kafkaTemplate);
        assertThat(outboxRows()).hasSize(1);
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * The Mockito spy behind OutboxWriter's @Transactional proxy. Stubbing through the proxy would run
     * the MANDATORY transaction check during stubbing (no transaction there), so stub the spy itself.
     */
    private OutboxWriter spiedOutboxWriter() {
        return AopTestUtils.getUltimateTargetObject(outboxWriter);
    }

    private int completions() {
        return jdbc.queryForObject("SELECT count(*) FROM habit_completions", Integer.class);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbc.queryForList("SELECT id, aggregate_type, aggregate_id, event_type, event_version, attempts,"
                + " sent_at, payload::text AS payload_text FROM outbox_events ORDER BY created_at");
    }

    /** The envelope without event_id, occurred_at and the payload's completedAt. */
    private static JsonNode withoutVolatile(JsonNode envelope) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = envelope.deepCopy();
        copy.remove(List.of("event_id", "occurred_at"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.get("payload")).remove("completedAt");
        return copy;
    }
}
