package com.habitflow.habits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.habitflow.habits.dto.HabitRequest;
import com.habitflow.habits.dto.HabitResponse;
import com.habitflow.habits.outbox.OutboxWriter;
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

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * createHabit writes habit.created to the outbox in the habit's transaction (R1-05b / HF-10).
 *
 * <p>Same harness as {@link ToggleOutboxIT}: Testcontainers Postgres only, Kafka at a closed port so
 * any Kafka call in the request path would block (max.block.ms), and the datasource from
 * {@code @DynamicPropertySource} so {@link TestcontainersOnly.Guard} can check it before Flyway runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@Testcontainers
class CreateHabitOutboxIT {

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
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    // Not used directly: if GuardConfig stops being picked up, this injection fails the context.
    @Autowired TestcontainersOnly.Guard testcontainersOnlyGuard;

    @SpyBean OutboxWriter outboxWriter;
    @SpyBean KafkaTemplate<?, ?> kafkaTemplate; // wildcard: matches Boot's KafkaTemplate<?, ?> bean

    @BeforeEach
    void clean() {
        // The spy bean lives across tests in the cached context: start each test with no recorded writes.
        clearInvocations(spiedOutboxWriter());
        clearInvocations(kafkaTemplate);
        jdbc.execute("TRUNCATE habit_completions, outbox_events, habits RESTART IDENTITY CASCADE");
    }

    @Test
    void create_writesOneHabitCreatedRow() throws Exception {
        HabitResponse created = habitService.createHabit(request(), USER_ID);

        assertThat(habits()).isEqualTo(1);
        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("event_type", "habit.created")
                .containsEntry("aggregate_type", "habit")
                .containsEntry("aggregate_id", created.getId().toString())
                .containsEntry("event_version", 1)
                .containsEntry("attempts", 0);
        assertThat(row.get("sent_at")).isNull();

        // jsonb reorders keys, so compare as JSON trees, never as strings.
        JsonNode envelope = objectMapper.readTree((String) row.get("payload_text"));
        assertThat(envelope.get("event_id").asText()).isEqualTo(row.get("id").toString());
        // Expected shape = the shared contract fixture, with this test's ids in place of the fixture's.
        ObjectNode expected = (ObjectNode) fixture("habit.created.v1.json");
        // Ids go in as parsed JSON (IntNode), like the actual tree: put(Long) would make a LongNode,
        // and IntNode(1) != LongNode(1) in JsonNode.equals.
        expected.put("aggregate_id", created.getId().toString());
        ((ObjectNode) expected.get("payload"))
                .set("habitId", objectMapper.readTree(created.getId().toString()));
        ((ObjectNode) expected.get("payload"))
                .set("userId", objectMapper.readTree(String.valueOf(USER_ID)));
        assertThat(withoutVolatile(envelope)).isEqualTo(withoutVolatile(expected));
        assertThat(envelope.get("payload").get("createdAt").isTextual()).isTrue();

        verify(spiedOutboxWriter(), times(1)).write(anyString(), anyString(), anyString(), any());
    }

    @Test
    void exceptionAfterOutboxWrite_rollsBackHabitAndOutboxRow() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            outboxEventRepository.flush(); // the row really reaches the database inside the transaction
            // Same connection (JpaTransactionManager exposes it to JdbcTemplate): both rows exist pre-rollback.
            assertThat(habits()).isEqualTo(1);
            assertThat(outboxRows()).hasSize(1);
            throw new IllegalStateException("boom after outbox write");
        }).when(spiedOutboxWriter()).write(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> habitService.createHabit(request(), USER_ID))
                .hasMessageContaining("boom after outbox write");

        assertThat(habits()).isZero();
        assertThat(outboxRows()).isEmpty();
        verify(spiedOutboxWriter(), times(1)).write(anyString(), anyString(), anyString(), any());
    }

    @Test
    void create_withKafkaUnreachable_completesUnderOneSecond_noKafkaCall() {
        long start = System.nanoTime();
        habitService.createHabit(request(), USER_ID);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Printed for the PR evidence; the assertion is the L2 bound (< 1 s, no 60 s max.block.ms).
        System.out.println("create_withKafkaUnreachable elapsed_ms=" + elapsed.toMillis());
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        verifyNoInteractions(kafkaTemplate);
        assertThat(outboxRows()).hasSize(1);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** A shared contract fixture from docs/events/ (on the test classpath under events/, see pom.xml). */
    private JsonNode fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/events/" + name)) {
            assertThat(in).as("classpath fixture /events/" + name).isNotNull();
            return objectMapper.readTree(in);
        }
    }

    private static HabitRequest request() {
        return new HabitRequest("Read 20 pages", "before bed", "DAILY");
    }

    /**
     * The Mockito spy behind OutboxWriter's @Transactional proxy. Stubbing through the proxy would run
     * the MANDATORY transaction check during stubbing (no transaction there), so stub the spy itself.
     */
    private OutboxWriter spiedOutboxWriter() {
        return AopTestUtils.getUltimateTargetObject(outboxWriter);
    }

    private int habits() {
        return jdbc.queryForObject("SELECT count(*) FROM habits", Integer.class);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbc.queryForList("SELECT id, aggregate_type, aggregate_id, event_type, event_version, attempts,"
                + " sent_at, payload::text AS payload_text FROM outbox_events ORDER BY created_at");
    }

    /** The envelope without event_id, occurred_at and the payload's createdAt. */
    private static JsonNode withoutVolatile(JsonNode envelope) {
        ObjectNode copy = envelope.deepCopy();
        copy.remove(List.of("event_id", "occurred_at"));
        ((ObjectNode) copy.get("payload")).remove("createdAt");
        return copy;
    }
}
