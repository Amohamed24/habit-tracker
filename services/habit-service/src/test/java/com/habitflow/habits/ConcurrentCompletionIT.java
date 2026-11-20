package com.habitflow.habits;

import com.habitflow.habits.client.AuthServiceClient;
import com.habitflow.habits.dto.UserValidationResponse;
import com.habitflow.habits.entity.Habit;
import com.habitflow.habits.outbox.OutboxWriter;
import com.habitflow.habits.repository.HabitRepository;
import com.habitflow.habits.support.TestcontainersOnly;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.AfterEach;
import org.skyscreamer.jsonassert.JSONAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * R1-08 / HF-14: two concurrent "complete" toggles produce one completion and one outbox event, and
 * the loser gets a clean 409.
 *
 * <p>The race is deterministic: A's toggle runs inside a TransactionTemplate on its own thread, so
 * the request returns its 200 while A's transaction (with the completion INSERT already executed,
 * IDENTITY) stays open on a latch. B's INSERT then blocks on the unique index; the test waits until
 * pg_stat_activity shows B waiting on a lock, releases A, and B fails with 23505.
 *
 * <p>Testcontainers Postgres only; Kafka points at a closed port (no Kafka call is on this path).
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@AutoConfigureMockMvc
@Testcontainers
class ConcurrentCompletionIT {

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
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired MockMvc mockMvc;
    @Autowired HabitRepository habitRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired @Qualifier("habitsCompletedCounter") Counter habitsCompletedCounter;
    // Not used directly: if GuardConfig stops being picked up, this injection fails the context.
    @Autowired TestcontainersOnly.Guard testcontainersOnlyGuard;

    @MockBean AuthServiceClient authServiceClient;
    @SpyBean OutboxWriter outboxWriter;

    private Long habitId;
    private ExecutorService executor;

    @BeforeEach
    void seed() {
        when(authServiceClient.validateToken(anyString()))
                .thenReturn(UserValidationResponse.builder().valid(true).userId(USER_ID).build());
        jdbc.execute("TRUNCATE habit_completions, outbox_events, habits RESTART IDENTITY CASCADE");
        habitId = habitRepository.save(Habit.builder()
                .name("Read 20 pages").frequency("DAILY").userId(USER_ID).build()).getId();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentToggleOn_one200One409_oneCompletionOneOutboxRow() throws Exception {
        double counterBefore = habitsCompletedCounter.count();
        CountDownLatch aRequestDone = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);

        // A: the request completes (200) inside a transaction that commits only after releaseA.
        Future<MvcResult> a = executor.submit(() -> transactionTemplate.execute(status -> {
            try {
                MvcResult result = toggle();
                aRequestDone.countDown();
                if (!releaseA.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("A was never released");
                }
                return result;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(aRequestDone.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("A's request finished").isTrue();

        // B: its INSERT blocks on the unique index behind A's uncommitted row.
        Future<MvcResult> b = executor.submit(this::toggle);
        await().atMost(TIMEOUT).until(this::insertIsWaitingOnLock);
        assertThat(completionsCommitted()).as("A not committed yet").isZero();

        releaseA.countDown();
        MvcResult winner = a.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        MvcResult loser = b.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(winner.getResponse().getStatus()).isEqualTo(200);
        assertThat(winner.getResponse().getContentAsString()).contains("\"completedToday\":true");

        String loserBody = loser.getResponse().getContentAsString();
        System.out.println("concurrentToggleOn loser status=" + loser.getResponse().getStatus() + " body=" + loserBody);
        assertThat(loser.getResponse().getStatus()).isEqualTo(409);
        JSONAssert.assertEquals(
                "{\"error\":\"COMPLETION_CONFLICT\",\"completedToday\":true}", loserBody, true);
        assertThat(loserBody)
                .doesNotContainIgnoringCase("duplicate")
                .doesNotContainIgnoringCase("insert")
                .doesNotContainIgnoringCase("sql")
                .doesNotContainIgnoringCase("constraint")
                .doesNotContain("habit_completions")
                .doesNotContain("uk_habit_completions_habit_date")
                .doesNotContain("uk1jmmm9bn1u8tqv76tvux1htde");

        assertThat(completionsCommitted()).isEqualTo(1);
        List<String> outboxTypes = jdbc.queryForList("SELECT event_type FROM outbox_events", String.class);
        assertThat(outboxTypes).containsExactly("habit.completed");
        // The loser never reached the outbox writer (not merely rolled back): exactly one call, A's.
        verify(spiedOutboxWriter(), times(1)).write(anyString(), anyString(), anyString(), any());
        assertThat(habitsCompletedCounter.count() - counterBefore).isEqualTo(1.0);
    }

    @Test
    void sequentialDoubleToggle_flipsOnThenOff() throws Exception {
        MvcResult on = toggle();
        assertThat(on.getResponse().getStatus()).isEqualTo(200);
        assertThat(on.getResponse().getContentAsString()).contains("\"completedToday\":true");

        MvcResult off = toggle();
        assertThat(off.getResponse().getStatus()).isEqualTo(200);
        assertThat(off.getResponse().getContentAsString()).contains("\"completedToday\":false");

        assertThat(completionsCommitted()).isZero();
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY created_at", String.class))
                .containsExactly("habit.completed", "habit.completion_undone");
    }

    // --- helpers -------------------------------------------------------------------------------

    private MvcResult toggle() throws Exception {
        return mockMvc.perform(post("/api/habits/{id}/toggle", habitId).header("Authorization", "Bearer x"))
                .andReturn();
    }

    /** True once a backend in the test database is blocked on a lock while inserting the completion. */
    private boolean insertIsWaitingOnLock() {
        Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = ? AND pid <> pg_backend_pid() AND state = 'active'
                  AND wait_event_type = 'Lock' AND query ILIKE 'insert into habit_completions%'
                """, Integer.class, postgres.getDatabaseName());
        return waiting != null && waiting > 0;
    }

    private int completionsCommitted() {
        return jdbc.queryForObject("SELECT count(*) FROM habit_completions", Integer.class);
    }

    /** Verify on the spy behind the @Transactional proxy: calling the proxy would hit the MANDATORY check. */
    private OutboxWriter spiedOutboxWriter() {
        return AopTestUtils.getUltimateTargetObject(outboxWriter);
    }
}
