package com.habitflow.habits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habitflow.habits.config.KafkaConfig;
import com.habitflow.habits.outbox.OutboxPublisher;
import com.habitflow.habits.outbox.OutboxStore;
import com.habitflow.habits.outbox.OutboxWriter;
import com.habitflow.habits.support.TestcontainersOnly;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Outbox publisher against real Postgres and a real Kafka broker (R1-06a / HF-11).
 *
 * <p>The poll loop is off ({@code outbox.publisher.enabled=false}): each test calls
 * {@link OutboxPublisher#publishBatch()} itself, so nothing else marks its rows sent. Records are read
 * back from the container with a plain consumer and matched by event_id, because the topic is not
 * cleared between tests.
 *
 * <p>{@code @Testcontainers} comes first and {@code @DirtiesContext} closes the context after the class,
 * so the producer is shut down before the Kafka container stops instead of reconnecting to it for the
 * rest of the failsafe run.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=false",
        // Short deadline so the un-acked-future test finishes quickly; real acks take milliseconds.
        "outbox.send-timeout-ms=2000"
})
@DirtiesContext
class OutboxPublisherIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    // Legacy KafkaContainer, same image as IntegrationTestBase (already pulled in CI).
    @Container
    @SuppressWarnings("deprecation")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @TestConfiguration
    static class GuardConfig {
        @Bean
        static TestcontainersOnly.Guard testcontainersOnlyGuard() {
            return new TestcontainersOnly.Guard(postgres::getJdbcUrl);
        }
    }

    private static final String AGGREGATE_ID = "42";

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxWriter outboxWriter;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    // Not used directly: if GuardConfig stops being picked up, this injection fails the context.
    @Autowired TestcontainersOnly.Guard testcontainersOnlyGuard;

    @SpyBean OutboxStore outboxStore;
    @SpyBean KafkaTemplate<?, ?> kafkaTemplate; // wildcard: matches Boot's KafkaTemplate<?, ?> bean

    @BeforeEach
    void clean() {
        // Spies live across tests in the cached context: drop earlier stubs and recorded calls.
        reset(spiedStore(), kafkaTemplate);
        jdbc.execute("TRUNCATE habit_completions, outbox_events, habits RESTART IDENTITY CASCADE");
    }

    @Test
    void publish_sendsRecordWithHeaders_andMarksSent() throws Exception {
        UUID eventId = write("habit.completed");

        assertThat(publisher.publishBatch()).isEqualTo(1);

        ConsumerRecord<String, String> record = awaitRecords(eventId, 1).get(0);
        assertThat(record.topic()).isEqualTo("habit-events");
        assertHeaders(record, "habit.completed");

        JsonNode body = objectMapper.readTree(record.value());
        assertThat(header(record.headers(), "event_id")).isEqualTo(eventId.toString())
                .isEqualTo(body.get("event_id").asText());
        assertThat(header(record.headers(), "event_version")).isEqualTo(body.get("event_version").asText());
        assertThat(record.key()).isEqualTo(AGGREGATE_ID).isEqualTo(body.get("aggregate_id").asText());
        // jsonb normalizes the text, so compare JSON trees with the stored row, never strings.
        assertThat(body).isEqualTo(objectMapper.readTree(payloadText(eventId)));

        assertThat(sentAt(eventId)).isNotNull();
    }

    @Test
    void exceptionAfterSend_rowStaysUnsent_andIsRepublished() {
        UUID eventId = write("habit.completed");
        // The UPDATE runs, then the batch throws before commit: the broker has the record, the DB rolls back.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("boom after send, before commit");
        }).when(spiedStore()).markSent(anyList());

        assertThatThrownBy(() -> publisher.publishBatch()).hasMessageContaining("boom after send");
        assertThat(sentAt(eventId)).as("rolled back").isNull();

        reset(spiedStore());
        assertThat(publisher.publishBatch()).as("re-claimed on the next batch").isEqualTo(1);
        assertThat(sentAt(eventId)).isNotNull();

        // D2 by design: the same event is on the topic twice; consumers de-duplicate by event_id.
        List<ConsumerRecord<String, String>> records = awaitRecords(eventId, 2);
        assertThat(records).hasSize(2);
        records.forEach(r -> assertHeaders(r, "habit.completed"));
    }

    @Test
    void emptyOutbox_isNoOp() {
        assertThat(publisher.publishBatch()).isZero();

        verify(spiedStore(), times(1)).claim(anyInt());
        verify(spiedStore(), never()).markSent(anyList());
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void failedSend_rowStaysUnsent_laterRowSameAggregateStillPublished() {
        UUID first = write("habit.completed");
        UUID later = write("habit.completion_undone");
        doReturn(CompletableFuture.failedFuture(new KafkaException("broker said no")))
                .when(template()).send(argThat(isEvent(first)));

        assertThat(publisher.publishBatch()).isEqualTo(1);

        assertThat(sentAt(first)).isNull();
        assertThat(sentAt(later)).as("ordering is not enforced: the later row is not blocked").isNotNull();
        assertHeaders(awaitRecords(later, 1).get(0), "habit.completion_undone");

        // Nothing about the failed row changed, so the next poll simply retries it.
        reset(kafkaTemplate);
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(sentAt(first)).isNotNull();
        assertHeaders(awaitRecords(first, 1).get(0), "habit.completed");
    }

    @Test
    void unackedSend_timesOutAtDeadline_rowStaysUnsent() {
        UUID eventId = write("habit.completed");
        doReturn(new CompletableFuture<>()).when(template()).send(any(ProducerRecord.class)); // never acked

        long start = System.nanoTime();
        int sent = publisher.publishBatch();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Printed for the PR evidence: the wait ends at outbox.send-timeout-ms (2 s here), not later.
        System.out.println("unackedSend elapsed_ms=" + elapsed.toMillis());
        assertThat(sent).isZero();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
        assertThat(sentAt(eventId)).isNull();
    }

    // --- helpers -------------------------------------------------------------------------------

    private UUID write(String eventType) {
        return tx.execute(status -> outboxWriter.write("habit", AGGREGATE_ID, eventType,
                Map.of("habitId", 42, "userId", 7))).getId();
    }

    /** Polls the topic from the start until {@code count} records with this event_id have arrived. */
    private List<ConsumerRecord<String, String>> awaitRecords(UUID eventId, int count) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaConfig.HABIT_EVENTS_TOPIC));
            await().atMost(Duration.ofSeconds(30)).pollInSameThread().pollInterval(Duration.ofMillis(50))
                    .until(() -> {
                        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
                            if (eventId.toString().equals(header(r.headers(), "event_id"))) {
                                found.add(r);
                            }
                        }
                        return found.size() >= count;
                    });
        }
        return found;
    }

    private static void assertHeaders(ConsumerRecord<String, String> record, String eventType) {
        Set<String> keys = new HashSet<>();
        for (Header h : record.headers()) {
            keys.add(h.key());
        }
        assertThat(keys).containsExactlyInAnyOrder("event_type", "event_version", "event_id");
        assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        assertThat(header(record.headers(), "event_type")).isEqualTo(eventType);
        assertThat(header(record.headers(), "event_version")).isEqualTo("1");
        assertThat(UUID.fromString(header(record.headers(), "event_id"))).isNotNull();
    }

    private static String header(Headers headers, String key) {
        Header h = headers.lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static org.mockito.ArgumentMatcher<ProducerRecord<String, String>> isEvent(UUID eventId) {
        return r -> r != null && eventId.toString().equals(header(r.headers(), "event_id"));
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> template() {
        return (KafkaTemplate<String, String>) kafkaTemplate;
    }

    /** The spy behind OutboxStore's @Transactional(MANDATORY) proxy: stub it, not the proxy. */
    private OutboxStore spiedStore() {
        return AopTestUtils.getUltimateTargetObject(outboxStore);
    }

    private Object sentAt(UUID id) {
        return jdbc.queryForObject("SELECT sent_at FROM outbox_events WHERE id = ?", Object.class, id);
    }

    private String payloadText(UUID id) {
        return jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE id = ?", String.class, id);
    }
}
