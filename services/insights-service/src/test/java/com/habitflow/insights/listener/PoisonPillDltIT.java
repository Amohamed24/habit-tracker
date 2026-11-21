package com.habitflow.insights.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.habitflow.insights.IntegrationTestBase;
import com.habitflow.insights.config.KafkaConfig;
import com.habitflow.insights.event.EventEnvelope;
import com.habitflow.insights.projection.ProjectionService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETED;
import static com.habitflow.insights.event.EventEnvelope.HABIT_CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Poison records and the dead-letter topic (R1-12a / HF-19), against Testcontainers Postgres (the base's
 * singleton) and a per-class Kafka. Runs under failsafe only.
 *
 * <p>Every test truncates both tables, uses a fresh user id, and finds its own DLT records by the
 * {@code kafka_dlt-original-partition}/{@code -offset} headers, since the Kafka container is shared by the class.
 * All records use key {@code "9"}, so poison and valid records of one test share a partition.
 */
@DirtiesContext // closed after the class so its running listener doesn't keep polling the stopped container
class PoisonPillDltIT extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.parse("2026-11-15");
    private static final AtomicLong NEXT_USER = new AtomicLong(70_000);
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String KEY = "9";
    /** Truncated JSON: JsonDeserializer fails on it. */
    private static final byte[] POISON = "{\"event_id\": ".getBytes(StandardCharsets.UTF_8);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> "true");
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
    }

    @SpyBean ProjectionService projection;
    @SpyBean KafkaTemplate<String, Object> deadLetterKafkaTemplate;
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

    /** AC: "Malformed JSON → on the DLT with `kafka_dlt-exception-*` headers and the original key and value bytes." */
    @Test
    void malformedJson_landsOnDlt_withExceptionHeadersAndOriginalBytes() throws Exception {
        RecordMetadata poison = send(POISON);

        ConsumerRecord<byte[], byte[]> dlt = awaitSingleDltRecord(poison);

        assertThat(dlt.key()).isEqualTo(KEY.getBytes(StandardCharsets.UTF_8));
        assertThat(dlt.value()).isEqualTo(POISON);
        assertThat(dlt.partition()).as("same partition number as the source").isEqualTo(poison.partition());
        assertThat(headerString(dlt, "kafka_dlt-exception-fqcn")).isNotBlank();
        assertThat(headerString(dlt, "kafka_dlt-exception-message")).isNotBlank();
        assertThat(headerString(dlt, "kafka_dlt-exception-stacktrace")).contains("DeserializationException");
        assertThat(headerString(dlt, "kafka_dlt-original-topic")).isEqualTo(KafkaConfig.HABIT_EVENTS_TOPIC);
        assertThat(headerInt(dlt, "kafka_dlt-original-partition")).isEqualTo(poison.partition());
        assertThat(headerLong(dlt, "kafka_dlt-original-offset")).isEqualTo(poison.offset());
        // ErrorHandlingDeserializer's raw-bytes header is not copied onto the DLT record.
        assertThat(dlt.headers().lastHeader("springDeserializerExceptionValue")).isNull();
    }

    /**
     * AC: "The next valid record is applied, with no loop." A poison record must not block later good records
     * on the same partition, and is dead-lettered exactly once.
     */
    @Test
    void poisonThenValid_samePartition_validIsApplied_noLoop() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        UUID validId = UUID.randomUUID();

        RecordMetadata poison = send(POISON);
        RecordMetadata valid = send(envelope(validId, HABIT_COMPLETED, user));
        assertThat(valid.partition()).isEqualTo(poison.partition());

        awaitCommittedPast(valid);
        assertThat(count(user)).isEqualTo(1);
        assertThat(processedRows(validId)).isEqualTo(1);
        assertThat(dltRecordsFor(poison)).as("dead-lettered once, not republished").hasSize(1);
        verify(projection).apply(argThat((EventEnvelope e) -> e != null && validId.equals(e.eventId())));
    }

    /** AC: "No `processed_events` row for the poison record." */
    @Test
    void poisonRecord_hasNoProcessedEventsRow() throws Exception {
        RecordMetadata poison = send(POISON);

        awaitCommittedPast(poison);
        awaitSingleDltRecord(poison);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isZero();
        // A deserialization failure never reaches the listener, so apply never sees it (not even as null).
        verify(projection, never()).apply((EventEnvelope) isNull());
        verify(projection, never()).apply(any(EventEnvelope.class));
    }

    /**
     * Offset rule (not a Jira AC): a poison record's offset is committed only after its DLT publish succeeded.
     * The DLT send fails twice; during each failure, the group's committed offset for the partition is exactly
     * the poison record's offset. The third send is real, and only then does the commit move past it.
     */
    @Test
    void poisonOffset_committedOnlyAfterDltPublishSucceeds() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        AtomicInteger dltSends = new AtomicInteger();
        List<Long> committedDuringFailure = new CopyOnWriteArrayList<>();
        List<TopicPartition> partition = new CopyOnWriteArrayList<>();

        // Same key → same partition: commit a probe first so the partition is known and has a committed offset.
        RecordMetadata probe = send(envelope(UUID.randomUUID(), HABIT_CREATED, user));
        partition.add(new TopicPartition(probe.topic(), probe.partition()));
        awaitCommittedPast(probe);

        doAnswer(invocation -> {
            ProducerRecord<?, ?> out = invocation.getArgument(0);
            if (out.value() instanceof byte[] v && Arrays.equals(v, POISON) && dltSends.incrementAndGet() <= 2) {
                Long committed = committedOffset(partition.get(0));
                committedDuringFailure.add(committed == null ? -1L : committed);
                return CompletableFuture.failedFuture(new KafkaException("forced DLT failure " + dltSends.get()));
            }
            return invocation.callRealMethod();
        }).when(deadLetterKafkaTemplate).send(any(ProducerRecord.class));

        RecordMetadata poison = send(POISON);

        awaitCommittedPast(poison);
        // The first sample (during the first failed send) can't catch a bug: no commit is possible before the
        // error handler returns. The real checks are the second sample, taken after the first failure was handled
        // (a commit-on-failure bug would already show there), and dltSends == 3 (the record was redelivered).
        assertThat(dltSends.get()).isEqualTo(3);
        assertThat(committedDuringFailure).hasSize(2)
                .allSatisfy(c -> assertThat(c).isEqualTo(poison.offset()));
        assertThat(dltRecordsFor(poison)).as("only the successful send reached the DLT").hasSize(1);
    }

    /**
     * Behaviour change from HF-19 (not a Jira AC): an apply failure is dead-lettered after the default backoff
     * (10 attempts; tuning and classification are HF-20), and the partition moves on.
     *
     * <p>Unlike a deserialization failure, the DLT value is <b>not</b> the original bytes: the record was already
     * deserialized, so the recoverer publishes the {@link EventEnvelope} re-serialized as JSON. This test sends a
     * value with an unknown field and extra whitespace and asserts the DLT value differs from it byte for byte,
     * has lost the unknown field, and still carries the same envelope fields. The key bytes are unchanged.
     */
    @Test
    void applyFailure_afterDefaultRetries_landsOnDlt() throws Exception {
        long user = NEXT_USER.incrementAndGet();
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        doThrow(new IllegalStateException("forced apply failure"))
                .when(projection).apply(argThat((EventEnvelope e) -> e != null && failing.equals(e.eventId())));

        ObjectNode original = envelopeNode(failing, HABIT_COMPLETED, user).put("x_original_only", true);
        byte[] originalBytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(original);
        RecordMetadata bad = send(originalBytes);
        RecordMetadata good = send(envelope(next, HABIT_COMPLETED, user));

        awaitCommittedPast(good);
        ConsumerRecord<byte[], byte[]> dlt = awaitSingleDltRecord(bad);

        assertThat(dlt.key()).isEqualTo(KEY.getBytes(StandardCharsets.UTF_8));
        assertThat(dlt.value()).as("re-serialized, not the original bytes").isNotEqualTo(originalBytes);
        JsonNode dltValue = json.readTree(dlt.value());
        assertThat(dltValue.has("x_original_only")).isFalse();
        assertThat(dltValue.get("event_id").asText()).isEqualTo(failing.toString());
        assertThat(dltValue.get("event_type").asText()).isEqualTo(HABIT_COMPLETED);
        assertThat(dltValue.get("payload").get("userId").asLong()).isEqualTo(user);
        assertThat(headerString(dlt, "kafka_dlt-exception-message")).contains("forced apply failure");
        assertThat(headerLong(dlt, "kafka_dlt-original-offset")).isEqualTo(bad.offset());

        assertThat(processedRows(failing)).isZero();
        assertThat(processedRows(next)).isEqualTo(1);
        assertThat(count(user)).isEqualTo(1);
    }

    /** The NewTopic bean: 3 partitions, so the resolver can keep the source partition number. */
    @Test
    void dltTopic_hasThreePartitions() throws Exception {
        TopicDescription dlt = admin.describeTopics(Set.of(KafkaConfig.DLT_TOPIC))
                .allTopicNames().get(30, TimeUnit.SECONDS).get(KafkaConfig.DLT_TOPIC);
        assertThat(dlt.partitions()).hasSize(3);
    }

    // --- helpers -------------------------------------------------------------------------------

    private ObjectNode envelopeNode(UUID eventId, String type, long userId) {
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
                .put("aggregate_id", KEY);
        root.set("payload", payload);
        return root;
    }

    private byte[] envelope(UUID eventId, String type, long userId) throws Exception {
        return json.writeValueAsBytes(envelopeNode(eventId, type, userId));
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

    private ConsumerRecord<byte[], byte[]> awaitSingleDltRecord(RecordMetadata source) {
        await().atMost(WAIT).until(() -> !dltRecordsFor(source).isEmpty());
        List<ConsumerRecord<byte[], byte[]>> records = dltRecordsFor(source);
        assertThat(records).hasSize(1);
        return records.get(0);
    }

    /** Every DLT record whose original-partition/offset headers point at {@code source}, read raw. */
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
