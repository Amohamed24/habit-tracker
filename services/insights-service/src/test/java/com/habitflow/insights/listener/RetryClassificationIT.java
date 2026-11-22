package com.habitflow.insights.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.habitflow.insights.IntegrationTestBase;
import com.habitflow.insights.config.KafkaConfig;
import com.habitflow.insights.event.EventEnvelope;
import com.habitflow.insights.projection.ProjectionService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

/**
 * Bounded exponential retry and error classification (R1-12b / HF-20, fixes L6), against Testcontainers Postgres
 * (the base's singleton) and a per-class Kafka. Runs under failsafe only.
 *
 * <p>The {@code @SpyBean} records a timestamp for every {@code apply} call on the test's own event id, then either
 * throws <b>before</b> calling the real method (so a failing attempt never opens the transaction or writes) or calls
 * it. Each test waits until the group's committed offset passes its record; that commit happens only after success
 * or after the DLT publish succeeded (HF-19), so every attempt is over when the assertions run.
 *
 * <p>The expected attempt counts and intervals (1 s, 2 s, 4 s) are literals from the Jira ticket, not constants
 * from main code, so a change to the handler can't silently move the expectations with it. Gaps are asserted as
 * lower bounds with a 50 ms tolerance below; no upper bound (CI jitter), the 30 s await is the ceiling.
 * Each test logs one {@code ATTEMPTS} line as evidence.
 */
@DirtiesContext // closed after the class so its running listener doesn't keep polling the stopped container
class RetryClassificationIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(RetryClassificationIT.class);

    private static final LocalDate DAY = LocalDate.parse("2026-11-15");
    private static final AtomicLong NEXT_USER = new AtomicLong(90_000);
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String KEY = "9";
    private static final long TOLERANCE_MS = 50;

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

    private final ObjectMapper json = new ObjectMapper();
    private KafkaProducer<String, byte[]> producer;
    private AdminClient admin;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE processed_events, daily_completion_counts");
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    @AfterEach
    void tearDown() {
        producer.close();
        admin.close();
    }

    /** AC: "Fails twice, then succeeds → applied once, nothing on the DLT." */
    @Test
    void failsTwiceThenSucceeds_appliedOnce_nothingOnDlt() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        UUID id = UUID.randomUUID();
        List<Long> attempts = spyAttempts(id, n -> n <= 2, "forced transient failure");

        RecordMetadata record = send(envelope(id, 1, user));
        awaitCommittedPast(record);
        List<ConsumerRecord<byte[], byte[]>> dlt = dltRecordsFor(record);
        logAttempts("failsTwiceThenSucceeds", id, attempts, dlt.size());

        assertThat(attempts).hasSize(3);
        assertGapsAtLeast(attempts, 1_000, 2_000);
        assertThat(count(user)).isEqualTo(1);
        assertThat(processedRows(id)).isEqualTo(1);
        assertThat(dlt).isEmpty();
    }

    /** AC: "Permanent failure → exactly 4 attempts, then the DLT." (a retryable exception that never stops) */
    @Test
    void permanentRetryableFailure_fourAttemptsThenDlt() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        UUID id = UUID.randomUUID();
        List<Long> attempts = spyAttempts(id, n -> true, "forced permanent failure");

        RecordMetadata record = send(envelope(id, 1, user));
        awaitCommittedPast(record);
        List<ConsumerRecord<byte[], byte[]>> dlt = dltRecordsFor(record);
        logAttempts("permanentRetryableFailure", id, attempts, dlt.size());

        assertThat(attempts).hasSize(4);
        assertGapsAtLeast(attempts, 1_000, 2_000, 4_000);
        assertThat(dlt).hasSize(1);
        assertThat(headerString(dlt.get(0), "kafka_dlt-exception-message")).contains("forced permanent failure");
        assertThat(headerLong(dlt.get(0), "kafka_dlt-original-offset")).isEqualTo(record.offset());
        assertThat(processedRows(id)).isZero();
        assertThat(count(user)).isNull();
    }

    /**
     * AC: "{@code event_version: 2} → DLT with no retries." The real apply throws IllegalArgumentException, which the
     * container wraps in ListenerExecutionFailedException; exactly 1 attempt also proves the classifier looks
     * through the wrapper to the cause (otherwise the wrapper would be retried 4 times).
     */
    @Test
    void eventVersion2_dltWithNoRetries() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        UUID id = UUID.randomUUID();
        List<Long> attempts = spyAttempts(id, n -> false, null);

        RecordMetadata record = send(envelope(id, 2, user));
        awaitCommittedPast(record);
        List<ConsumerRecord<byte[], byte[]>> dlt = dltRecordsFor(record);
        logAttempts("eventVersion2", id, attempts, dlt.size());

        assertThat(attempts).hasSize(1);
        assertThat(dlt).hasSize(1);
        assertThat(headerString(dlt.get(0), "kafka_dlt-exception-message")).contains("unsupported event_version 2");
        assertThat(headerLong(dlt.get(0), "kafka_dlt-original-offset")).isEqualTo(record.offset());
        assertThat(processedRows(id)).isZero();
        assertThat(count(user)).isNull();
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Stubs apply for {@code id} only: records System.nanoTime() first, then throws an IllegalStateException
     * (retryable) when {@code failOn} matches the 1-based attempt number, before the real method runs;
     * otherwise calls the real method.
     */
    private List<Long> spyAttempts(UUID id, IntPredicate failOn, String message) {
        List<Long> attemptNanos = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            attemptNanos.add(System.nanoTime());
            int attempt = attemptNanos.size();
            if (failOn.test(attempt)) {
                throw new IllegalStateException(message + " " + attempt);
            }
            return invocation.callRealMethod();
        }).when(projection).apply(argThat((EventEnvelope e) -> e != null && id.equals(e.eventId())));
        return attemptNanos;
    }

    private static List<Long> gapsMs(List<Long> attemptNanos) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < attemptNanos.size(); i++) {
            gaps.add(TimeUnit.NANOSECONDS.toMillis(attemptNanos.get(i) - attemptNanos.get(i - 1)));
        }
        return gaps;
    }

    private static void assertGapsAtLeast(List<Long> attemptNanos, long... expectedMs) {
        List<Long> gaps = gapsMs(attemptNanos);
        assertThat(gaps).hasSize(expectedMs.length);
        for (int i = 0; i < expectedMs.length; i++) {
            assertThat(gaps.get(i)).as("gap %d (configured %d ms)", i + 1, expectedMs[i])
                    .isGreaterThanOrEqualTo(expectedMs[i] - TOLERANCE_MS);
        }
    }

    private static void logAttempts(String test, UUID id, List<Long> attemptNanos, int dlt) {
        log.info("ATTEMPTS test={} event_id={} attempts={} gaps_ms={} dlt={}",
                test, id, attemptNanos.size(), gapsMs(attemptNanos), dlt);
    }

    private byte[] envelope(UUID eventId, int version, long userId) throws Exception {
        ObjectNode payload = json.createObjectNode()
                .put("habitId", 9)
                .put("userId", userId)
                .put("habitName", "Read 20 pages")
                .put("completionDate", DAY.toString());
        ObjectNode root = json.createObjectNode()
                .put("event_id", eventId.toString())
                .put("event_type", HABIT_COMPLETED)
                .put("event_version", version)
                .put("occurred_at", Instant.now().toString())
                .put("aggregate_id", KEY);
        root.set("payload", payload);
        return json.writeValueAsBytes(root);
    }

    private RecordMetadata send(byte[] value) throws Exception {
        return producer.send(new ProducerRecord<>(KafkaConfig.HABIT_EVENTS_TOPIC, KEY, value)).get(30, TimeUnit.SECONDS);
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

    /** Every DLT record whose original-partition/offset headers point at {@code source}, read raw to the end offsets. */
    private List<ConsumerRecord<byte[], byte[]>> dltRecordsFor(RecordMetadata source) {
        List<ConsumerRecord<byte[], byte[]>> matches = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            List<TopicPartition> parts = new ArrayList<>();
            for (int p = 0; p < KafkaConfig.DLT_PARTITIONS; p++) {
                parts.add(new TopicPartition(KafkaConfig.DLT_TOPIC, p));
            }
            consumer.assign(parts);
            consumer.seekToBeginning(parts);
            Map<TopicPartition, Long> end = consumer.endOffsets(parts);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (parts.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp)) && System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    if (source.topic().equals(headerString(r, "kafka_dlt-original-topic"))
                            && headerInt(r, "kafka_dlt-original-partition") == source.partition()
                            && headerLong(r, "kafka_dlt-original-offset") == source.offset()) {
                        matches.add(r);
                    }
                }
            }
            List<String> behind = parts.stream().filter(tp -> consumer.position(tp) < end.get(tp))
                    .map(tp -> tp + " position=" + consumer.position(tp) + " end=" + end.get(tp)).toList();
            assertThat(behind).as("timed out after 10 s before reading the DLT to its end offsets; "
                    + "matches so far would be partial").isEmpty();
        }
        return matches;
    }

    private static Header header(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        assertThat(h).as("header " + name).isNotNull();
        return h;
    }

    private static String headerString(ConsumerRecord<?, ?> r, String name) {
        return new String(header(r, name).value(), StandardCharsets.UTF_8);
    }

    private static int headerInt(ConsumerRecord<?, ?> r, String name) {
        return ByteBuffer.wrap(header(r, name).value()).getInt();
    }

    private static long headerLong(ConsumerRecord<?, ?> r, String name) {
        return ByteBuffer.wrap(header(r, name).value()).getLong();
    }

    private Integer count(long userId) {
        return jdbc.query("SELECT completed_count FROM daily_completion_counts WHERE user_id = ? AND completion_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null, userId, DAY);
    }

    private int processedRows(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class, ProjectionService.CONSUMER, eventId);
    }
}
