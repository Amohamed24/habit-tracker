package com.habitflow.insights.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.habitflow.insights.IntegrationTestBase;
import com.habitflow.insights.config.KafkaConfig;
import com.habitflow.insights.event.EventEnvelope;
import com.habitflow.insights.projection.ProjectionService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETED;
import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETION_UNDONE;
import static com.habitflow.insights.event.EventEnvelope.HABIT_CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * HabitEventsListener against Testcontainers Postgres (the base's singleton) and Kafka (R1-11 / HF-18).
 *
 * <p>Records are produced the way habit-service's OutboxPublisher sends them: {@code StringSerializer} JSON text,
 * key = aggregate id, headers {@code event_type}/{@code event_version}/{@code event_id}, no {@code __TypeId__}.
 * Every test truncates both tables and uses fresh user ids (the Postgres is shared by every IT context), and
 * asserts on Kafka offsets/records from this test only, since the Kafka container is shared by the class.
 *
 * <p>The {@code @SpyBean} calls the real ProjectionService unless a test stubs one event_id; it also lets tests
 * verify a redelivery really reached {@code apply} (otherwise "count unchanged" would pass vacuously).
 */
@DirtiesContext // closed after the class so its running listener doesn't keep polling the stopped container
class HabitEventsListenerIT extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.parse("2026-11-15");
    private static final AtomicLong NEXT_USER = new AtomicLong(50_000);
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> "true");
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @SpyBean ProjectionService projection;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;

    private final ObjectMapper json = new ObjectMapper();
    private KafkaProducer<String, String> producer;
    private AdminClient admin;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE processed_events, daily_completion_counts");
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    @AfterEach
    void tearDown() {
        producer.close();
        admin.close();
    }

    /** IT analogue of AC "On compose: toggle on → count 1 within 5 s; toggle off → 0." (compose run in the PR). */
    @Test
    void completedThenUndone_viaKafka_endsAtZero() throws Exception {
        long user = NEXT_USER.incrementAndGet();

        send(envelope(UUID.randomUUID(), HABIT_COMPLETED, user));
        await().atMost(WAIT).until(() -> Integer.valueOf(1).equals(count(user)));

        send(envelope(UUID.randomUUID(), HABIT_COMPLETION_UNDONE, user));
        await().atMost(WAIT).until(() -> Integer.valueOf(0).equals(count(user)));
    }

    /** AC: "The same record produced twice → 1." */
    @Test
    void sameRecordProducedTwice_countsOnce() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        UUID eventId = UUID.randomUUID();
        String value = envelope(eventId, HABIT_COMPLETED, user);

        send(value);
        RecordMetadata second = send(value);

        awaitCommittedPast(second);
        assertThat(count(user)).isEqualTo(1);
        assertThat(processedRows(List.of(eventId))).isEqualTo(1);
        // Both copies reached apply (APPLIED, then DUPLICATE): the second wasn't just lost.
        verify(projection, times(2)).apply(argThat((EventEnvelope e) -> e != null && eventId.equals(e.eventId())));
    }

    /**
     * AC: "Restarting insights-service mid-stream → no double counts." Three completions are consumed and
     * committed, the consumer stops, the group's offset is rewound to before them (what a crash between the DB
     * commit and the offset commit leaves behind), two more arrive while it's down, and it restarts.
     */
    @Test
    void redeliveryAfterRestart_noDoubleCount() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        List<UUID> before = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<UUID> during = List.of(UUID.randomUUID(), UUID.randomUUID());

        List<RecordMetadata> sent = new ArrayList<>();
        for (UUID id : before) {
            sent.add(send(envelope(id, HABIT_COMPLETED, user)));
        }
        awaitCommittedPast(sent.get(2));
        assertThat(count(user)).isEqualTo(3);

        MessageListenerContainer container = listenerRegistry.getListenerContainers().iterator().next();
        container.stop();
        TopicPartition tp = new TopicPartition(sent.get(0).topic(), sent.get(0).partition());
        // Retried: the group must be empty (the stopped member's leave has to land) before offsets can change.
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> admin.alterConsumerGroupOffsets(
                ProjectionService.CONSUMER, Map.of(tp, new OffsetAndMetadata(sent.get(0).offset())))
                .all().get(10, TimeUnit.SECONDS));
        assertThat(committedOffset(tp)).isEqualTo(sent.get(0).offset());

        RecordMetadata last = null;
        for (UUID id : during) {
            last = send(envelope(id, HABIT_COMPLETED, user));
        }
        container.start();

        awaitCommittedPast(last);
        assertThat(count(user)).isEqualTo(5);
        List<UUID> all = new ArrayList<>(before);
        all.addAll(during);
        assertThat(processedRows(all)).isEqualTo(5);
        // The first three really were redelivered (APPLIED, then DUPLICATE after the restart).
        for (UUID id : before) {
            verify(projection, times(2)).apply(argThat((EventEnvelope e) -> e != null && id.equals(e.eventId())));
        }
    }

    /** AC: "`habit.created` is acknowledged and ignored." */
    @Test
    void habitCreated_isAcknowledgedAndIgnored() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        UUID eventId = UUID.randomUUID();

        RecordMetadata created = send(envelope(eventId, HABIT_CREATED, user));

        awaitCommittedPast(created);
        assertThat(processedRows(List.of(eventId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_completion_counts WHERE user_id = ?",
                Integer.class, user)).isZero();
    }

    /**
     * Offset rule (not a Jira AC): a record is committed only after apply() returns. apply throws twice for one
     * event_id, then runs for real. On each failing attempt, from inside the listener thread, the group's
     * committed offset for that partition is exactly the record's offset (not past it); after the
     * successful attempt it moves past the record.
     */
    @Test
    void failedApply_isNotCommittedUntilApplyReturns() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        List<Long> committedDuringFailure = new CopyOnWriteArrayList<>();
        AtomicLong recordOffset = new AtomicLong(-1);
        List<TopicPartition> partition = new CopyOnWriteArrayList<>();

        doAnswer(invocation -> {
            if (attempts.incrementAndGet() <= 2) {
                Long committed = committedOffset(partition.get(0));
                committedDuringFailure.add(committed == null ? -1L : committed);
                throw new IllegalStateException("forced failure " + attempts.get());
            }
            return invocation.callRealMethod();
        }).when(projection).apply(argThat((EventEnvelope e) -> e != null && eventId.equals(e.eventId())));

        // Partition is known before sending (same key → same partition), so the stub can read it.
        RecordMetadata probe = send(envelope(UUID.randomUUID(), HABIT_CREATED, user));
        partition.add(new TopicPartition(probe.topic(), probe.partition()));
        awaitCommittedPast(probe);

        RecordMetadata record = send(envelope(eventId, HABIT_COMPLETED, user));
        recordOffset.set(record.offset());

        awaitCommittedPast(record);
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(committedDuringFailure).hasSize(2)
                // The probe (same partition, just before) is committed first, so exactly the failing record.
                .allSatisfy(c -> assertThat(c).isEqualTo(recordOffset.get()));
        assertThat(count(user)).isEqualTo(1);
        assertThat(processedRows(List.of(eventId))).isEqualTo(1);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Fixture-shaped (docs/events/habit.*.v1.json) envelope JSON with a fixed habit id 9. */
    private String envelope(UUID eventId, String type, long userId) throws Exception {
        ObjectNode payload = json.createObjectNode()
                .put("habitId", 9)
                .put("userId", userId)
                .put("habitName", "Read 20 pages")
                .put("completionDate", DAY.toString());
        ObjectNode root = json.createObjectNode()
                .put("event_id", eventId.toString())
                .put("event_type", type)
                .put("event_version", 1)
                .put("occurred_at", Instant.now().toString())
                .put("aggregate_id", "9");
        root.set("payload", payload);
        return json.writeValueAsString(root);
    }

    /** As OutboxPublisher sends: key = aggregate id, three headers, plain JSON text. */
    private RecordMetadata send(String value) throws Exception {
        var tree = json.readTree(value);
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaConfig.HABIT_EVENTS_TOPIC, "9", value);
        record.headers()
                .add("event_type", tree.get("event_type").asText().getBytes(StandardCharsets.UTF_8))
                .add("event_version", "1".getBytes(StandardCharsets.UTF_8))
                .add("event_id", tree.get("event_id").asText().getBytes(StandardCharsets.UTF_8));
        return producer.send(record).get(30, TimeUnit.SECONDS);
    }

    private Long committedOffset(TopicPartition tp) throws Exception {
        OffsetAndMetadata om = admin.listConsumerGroupOffsets(ProjectionService.CONSUMER)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(tp);
        return om == null ? null : om.offset();
    }

    private void awaitCommittedPast(RecordMetadata md) {
        TopicPartition tp = new TopicPartition(md.topic(), md.partition());
        await().atMost(WAIT).until(() -> {
            Long committed = committedOffset(tp);
            return committed != null && committed > md.offset();
        });
    }

    private Integer count(long userId) {
        return jdbc.query("SELECT completed_count FROM daily_completion_counts WHERE user_id = ? AND completion_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null, userId, DAY);
    }

    private int processedRows(List<UUID> eventIds) {
        int rows = 0;
        for (UUID id : eventIds) {
            rows += jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                    Integer.class, ProjectionService.CONSUMER, id);
        }
        return rows;
    }
}
