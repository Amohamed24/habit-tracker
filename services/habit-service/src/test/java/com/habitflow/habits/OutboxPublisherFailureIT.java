package com.habitflow.habits;

import com.habitflow.habits.outbox.OutboxBackoff;
import com.habitflow.habits.outbox.OutboxPublisher;
import com.habitflow.habits.outbox.OutboxWriter;
import com.habitflow.habits.support.TestcontainersOnly;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Publisher failure handling (R1-07 / HF-13) against real Postgres, with no broker at all.
 *
 * <p>Kafka points at a closed port ({@code localhost:1}). The real {@link KafkaTemplate} is a spy: tests
 * stub {@code send()} with acked, failed or never-completing futures, and the broker-down test leaves it
 * unstubbed so the real producer times out. That the context starts at all with no broker is AC 5.
 *
 * <p>Backoff jitter is fixed at 1.0 (full base delay) so delays are exact. Every time is measured against
 * the database clock ({@code next_attempt_at - clock_timestamp()}), never the JVM's, and every row is read
 * back with JdbcTemplate: next_attempt_at is not writable through the entity.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=false",
        // Short ack deadline for the never-acked test; real metadata wait for the broker-down test.
        "outbox.send-timeout-ms=1000",
        "spring.kafka.producer.properties.max.block.ms=500",
        // KafkaAdmin can't create habit-events without a broker; it logs and continues (fail-fast is false).
        // Short timeouts only keep that startup wait out of the test run.
        "spring.kafka.admin.operation-timeout=2s",
        "spring.kafka.admin.close-timeout=1s",
        "spring.kafka.admin.properties.request.timeout.ms=1000",
        "spring.kafka.admin.properties.default.api.timeout.ms=2000"
})
@DirtiesContext
class OutboxPublisherFailureIT {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherFailureIT.class);

    /** A closed port: nothing listens on it, so every real connection attempt fails. */
    static final String NO_BROKER = "localhost:1";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> NO_BROKER);
    }

    @TestConfiguration
    static class Config {
        @Bean
        static TestcontainersOnly.Guard testcontainersOnlyGuard() {
            return new TestcontainersOnly.Guard(postgres::getJdbcUrl);
        }

        /** Jitter fixed at the top of its range: delay = full base, min(2^attempts s, 5 min). */
        @Bean
        @Primary
        OutboxBackoff fixedJitterBackoff() {
            return new OutboxBackoff(() -> 1.0);
        }
    }

    private static final String AGGREGATE_ID = "42";
    private static final String PAYLOAD_MARKER = "payload-marker-must-not-leak";

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxWriter outboxWriter;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConfigurableApplicationContext context;
    // Not used directly: if Config stops being picked up, this injection fails the context.
    @Autowired TestcontainersOnly.Guard testcontainersOnlyGuard;

    @SpyBean KafkaTemplate<?, ?> kafkaTemplate; // wildcard: matches Boot's KafkaTemplate<?, ?> bean

    @BeforeEach
    void clean() {
        reset(kafkaTemplate);
        jdbc.execute("TRUNCATE habit_completions, outbox_events, habits RESTART IDENTITY CASCADE");
    }

    @Test
    void failedFuture_recordsAttemptErrorAndBackoff() {
        UUID id = write("habit.completed");
        String longMessage = "x".repeat(5_000);
        doReturn(CompletableFuture.failedFuture(new KafkaException("Send failed", new RecordTooLargeException(longMessage))))
                .when(template()).send(any(ProducerRecord.class));

        assertThat(publisher.publishBatch()).isZero();

        Row row = row(id);
        assertThat(row.sentAt).isNull();
        assertThat(row.attempts).isEqualTo(1);
        assertThat(row.lastError)
                .startsWith("org.apache.kafka.common.errors.RecordTooLargeException: xxx")
                .hasSize(1_000)
                .doesNotContain(PAYLOAD_MARKER, "event_id", "habit.completed");
        // First failure: 2^1 s at full jitter, applied by the DB. In the future, at most 2 s away.
        assertThat(row.dueInSeconds).isGreaterThan(1.0).isLessThanOrEqualTo(2.0);
    }

    @Test
    void timedOutFuture_recordsTimeoutAndBackoff() {
        UUID id = write("habit.completed");
        doReturn(new CompletableFuture<>()).when(template()).send(any(ProducerRecord.class)); // never acked

        assertThat(publisher.publishBatch()).isZero();

        Row row = row(id);
        assertThat(row.sentAt).isNull();
        assertThat(row.attempts).isEqualTo(1);
        assertThat(row.lastError).isEqualTo("java.util.concurrent.TimeoutException: no ack within 1000 ms");
        // Measured from clock_timestamp() at the UPDATE, after the 1 s wait: still a full 2 s ahead.
        assertThat(row.dueInSeconds).isGreaterThan(1.0).isLessThanOrEqualTo(2.0);
    }

    @Test
    void rowInBackoff_isNotReclaimedUntilDue() {
        UUID id = write("habit.completed");
        doReturn(CompletableFuture.failedFuture(new KafkaException("broker said no")))
                .when(template()).send(any(ProducerRecord.class));
        assertThat(publisher.publishBatch()).isZero();
        assertThat(row(id).attempts).isEqualTo(1);

        reset(kafkaTemplate);
        stubAckAll();
        assertThat(publisher.publishBatch()).as("in backoff: not claimed").isZero();
        verify(template(), never()).send(any(ProducerRecord.class));
        assertThat(row(id).sentAt).isNull();

        // Make it due by DB time, not by waiting.
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE id = ?", id);
        assertThat(publisher.publishBatch()).as("due: re-claimed and acked").isEqualTo(1);
        Row row = row(id);
        assertThat(row.sentAt).isNotNull();
        assertThat(row.attempts).as("a success does not count as an attempt").isEqualTo(1);
    }

    @Test
    void mixedBatch_marksOnlyAckedRowsSent() {
        UUID first = write("habit.completed");
        UUID failing = write("habit.completion_undone");
        UUID third = write("habit.completed");
        stubAckAll();
        doReturn(CompletableFuture.failedFuture(new KafkaException("broker said no")))
                .when(template()).send(argThat(isEvent(failing)));

        assertThat(publisher.publishBatch()).isEqualTo(2);

        for (UUID acked : new UUID[] {first, third}) {
            Row row = row(acked);
            assertThat(row.sentAt).as("acked %s", acked).isNotNull();
            assertThat(row.attempts).isZero();
            assertThat(row.lastError).isNull();
        }
        Row failed = row(failing);
        assertThat(failed.sentAt).isNull();
        assertThat(failed.attempts).isEqualTo(1);
        assertThat(failed.lastError).isEqualTo("org.apache.kafka.common.KafkaException: broker said no");
        assertThat(failed.dueInSeconds).isGreaterThan(1.0).isLessThanOrEqualTo(2.0);
    }

    @Test
    void backoff_capsAtFiveMinutes() {
        UUID id = write("habit.completed");
        jdbc.update("UPDATE outbox_events SET attempts = 20 WHERE id = ?", id); // 2^21 s uncapped
        doReturn(CompletableFuture.failedFuture(new KafkaException("broker said no")))
                .when(template()).send(any(ProducerRecord.class));

        assertThat(publisher.publishBatch()).isZero();

        Row row = row(id);
        assertThat(row.attempts).isEqualTo(21);
        assertThat(row.dueInSeconds).isGreaterThan(290.0).isLessThanOrEqualTo(300.0);
    }

    @Test
    void brokerDown_contextStarts_batchBacksOff() {
        // AC 5: this context started with no broker reachable at all.
        assertThat(context.isActive()).isTrue();
        UUID first = write("habit.completed");
        UUID second = write("habit.completion_undone");

        // Unstubbed: the real producer waits max.block.ms (500 ms) for metadata that never comes.
        long start = System.nanoTime();
        assertThat(publisher.publishBatch()).isZero();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        log.info("brokerDown publishBatch elapsed_ms={}", elapsed.toMillis());

        Row a = row(first);
        Row b = row(second);
        log.info("brokerDown last_error first=[{}] second=[{}]", a.lastError, b.lastError);
        assertThat(a.sentAt).isNull();
        assertThat(a.attempts).isEqualTo(1);
        assertThat(a.lastError).startsWith("org.apache.kafka.common.errors.TimeoutException: ");
        // The synchronous failure stops the batch; the row never sent backs off with it.
        assertThat(b.sentAt).isNull();
        assertThat(b.attempts).isEqualTo(1);
        assertThat(b.lastError).startsWith("not attempted: org.apache.kafka.common.errors.TimeoutException: ");
        assertThat(a.dueInSeconds).isGreaterThan(1.0).isLessThanOrEqualTo(2.0);
        assertThat(b.dueInSeconds).isGreaterThan(1.0).isLessThanOrEqualTo(2.0);
        // One metadata wait for the whole batch, not one per row.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        verify(template()).send(any(ProducerRecord.class));
    }

    // --- helpers -------------------------------------------------------------------------------

    private record Row(Object sentAt, int attempts, String lastError, double dueInSeconds) {
    }

    /** The row as the DB has it; dueInSeconds = next_attempt_at - clock_timestamp(), on the DB clock. */
    private Row row(UUID id) {
        return jdbc.queryForObject("""
                SELECT sent_at, attempts, last_error,
                       EXTRACT(EPOCH FROM next_attempt_at - clock_timestamp())::float8 AS due_in
                FROM outbox_events WHERE id = ?
                """, (rs, i) -> new Row(rs.getObject("sent_at"), rs.getInt("attempts"),
                rs.getString("last_error"), rs.getDouble("due_in")), id);
    }

    private UUID write(String eventType) {
        return tx.execute(status -> outboxWriter.write("habit", AGGREGATE_ID, eventType,
                Map.of("habitId", 42, "userId", 7, "note", PAYLOAD_MARKER))).getId();
    }

    /** Every send is acked at once with made-up metadata (no broker involved). */
    private void stubAckAll() {
        doAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            RecordMetadata meta = new RecordMetadata(new TopicPartition(record.topic(), 0), 0L, 0, 0L, 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(record, meta));
        }).when(template()).send(any(ProducerRecord.class));
    }

    private static org.mockito.ArgumentMatcher<ProducerRecord<String, String>> isEvent(UUID eventId) {
        return r -> {
            if (r == null) {
                return false;
            }
            Header h = r.headers().lastHeader("event_id");
            return h != null && eventId.toString().equals(new String(h.value(), StandardCharsets.UTF_8));
        };
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> template() {
        return (KafkaTemplate<String, String>) kafkaTemplate;
    }
}
