package com.habitflow.insights.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.habitflow.insights.IntegrationTestBase;
import com.habitflow.insights.config.KafkaConfig;
import com.habitflow.insights.event.EventEnvelope;
import com.habitflow.insights.projection.ApplyResult;
import com.habitflow.insights.projection.ProjectionService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETED;
import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETION_UNDONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Testcontainers proof #1 (R1-14 / HF-22): duplicates, reordering and a full replay count once (proves D1, D2).
 *
 * <p>500 distinct envelopes (completed/undone, 20 users x 3 dates, 3 habits per user) plus 100 exact duplicates
 * (20% of 500: same bytes, key and headers, as an outbox republish) are produced in a seeded shuffled order
 * through real Kafka to the running {@link HabitEventsListener}. Reordering is real on two levels: within a
 * partition Kafka delivers in produce order, so the shuffle puts some undones at a lower offset than the
 * completed they follow (counted from the actual offsets); across partitions a (user, date) cell's events come
 * from 3 habit keys on different partitions, which Kafka doesn't order at all. Then the group is rewound to
 * earliest with {@code AdminClient.alterConsumerGroupOffsets} and all 600 records are consumed again.
 *
 * <p>Own Kafka container on purpose (not a shared one): the replay reads from earliest, so the topic must hold
 * only this test's 600 records. Limits: one consumer instance (default concurrency 1), no mid-stream rebalance.
 *
 * <p>Rerun a failing seed with {@code ./mvnw -B verify -Dit.test=DuplicateDeliveryProofIT -Dproof.seed=<n>}.
 */
@DirtiesContext // closed after the class so its running listener doesn't keep polling the stopped container
class DuplicateDeliveryProofIT extends IntegrationTestBase {

    /** First static field: the time bound covers this class's Kafka start, context and test. */
    private static final long CLASS_START = System.nanoTime();

    private static final Logger log = LoggerFactory.getLogger(DuplicateDeliveryProofIT.class);

    // Jira HF-22 numbers.
    private static final int DISTINCT = 500;
    private static final int USERS = 20;
    private static final int DATES = 3;
    private static final int DUPLICATES = DISTINCT * 20 / 100; // "20% duplicates" = 100 extra copies
    private static final Duration CI_BOUND = Duration.ofSeconds(90);

    private static final int HABITS_PER_USER = 3;
    private static final long FIRST_USER = 90_001;
    private static final LocalDate FIRST_DATE = LocalDate.parse("2026-11-13");
    private static final long DEFAULT_SEED = 20261121L;
    private static final Duration PHASE_WAIT = Duration.ofSeconds(60);

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
    private final List<ApplyResult> results = new CopyOnWriteArrayList<>();
    private KafkaProducer<String, String> producer;
    private AdminClient admin;

    /** One generated envelope. seq = its position in its (user, habit, date) toggle history. */
    private record Event(UUID id, String type, long user, long habit, LocalDate date, int seq, String value) {
        String key() {
            return Long.toString(habit);
        }

        Cell cell() {
            return new Cell(user, date);
        }

        String slot() {
            return habit + "/" + date;
        }

        int delta() {
            return HABIT_COMPLETED.equals(type) ? 1 : -1;
        }
    }

    private record Cell(long user, LocalDate date) implements Comparable<Cell> {
        @Override
        public int compareTo(Cell o) {
            int c = Long.compare(user, o.user);
            return c != 0 ? c : date.compareTo(o.date);
        }

        @Override
        public String toString() {
            return user + "@" + date;
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE processed_events, daily_completion_counts");
        // Real method always runs; only the result is recorded (never stubbed), so duplicates and the replay
        // provably reached apply() and "unchanged" can't pass vacuously.
        doAnswer(invocation -> {
            ApplyResult r = (ApplyResult) invocation.callRealMethod();
            results.add(r);
            return r;
        }).when(projection).apply(any(EventEnvelope.class));
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    @AfterEach
    void tearDown() {
        producer.close();
        admin.close();
    }

    @Test
    void fiveHundredEvents_withDuplicatesReorderingAndReplay_countOnce() throws Exception {
        long seed = Long.getLong("proof.seed", DEFAULT_SEED);
        log.info("DuplicateDeliveryProofIT seed={} (rerun: -Dproof.seed={})", seed, seed);
        Random random = new Random(seed);

        // --- generate: 500 distinct, expected counts, 100 duplicates, shuffle -------------------------------
        List<Event> distinct = generate(random);
        assertThat(distinct).hasSize(DISTINCT);
        assertThat(distinct.stream().map(Event::id).distinct().count()).isEqualTo(DISTINCT);
        assertThat(distinct.stream().map(Event::user).distinct().count()).isEqualTo(USERS);
        assertThat(distinct.stream().map(Event::date).distinct().count()).isEqualTo(DATES);
        Map<Cell, Integer> expected = new TreeMap<>();
        for (Event e : distinct) {
            expected.merge(e.cell(), e.delta(), Integer::sum);
        }

        List<Event> picks = new ArrayList<>(distinct);
        Collections.shuffle(picks, random);
        List<Event> wire = new ArrayList<>(distinct);
        wire.addAll(picks.subList(0, DUPLICATES)); // 100 distinct event_ids, each sent twice
        Collections.shuffle(wire, random);
        assertThat(wire).hasSize(DISTINCT + DUPLICATES);

        // --- produce --------------------------------------------------------------------------------------
        long t0 = System.nanoTime();
        List<Future<RecordMetadata>> futures = new ArrayList<>();
        for (Event e : wire) {
            futures.add(producer.send(record(e)));
        }
        producer.flush();
        List<RecordMetadata> sent = new ArrayList<>();
        for (Future<RecordMetadata> f : futures) {
            sent.add(f.get(30, TimeUnit.SECONDS));
        }
        long produceMs = millisSince(t0);

        List<TopicPartition> partitions = IntStream.range(0, KafkaConfig.HABIT_EVENTS_PARTITIONS)
                .mapToObj(p -> new TopicPartition(KafkaConfig.HABIT_EVENTS_TOPIC, p)).toList();
        Map<TopicPartition, Long> end = offsets(partitions, OffsetSpec.latest());
        Map<TopicPartition, Long> earliest = offsets(partitions, OffsetSpec.earliest());
        assertThat(end.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(DISTINCT + DUPLICATES);
        assertThat(earliest.values()).allMatch(o -> o == 0L);

        // --- measured reordering (from the real offsets) -------------------------------------------------
        Map<UUID, Long> firstOffset = new HashMap<>();
        Map<UUID, Integer> partitionOf = new HashMap<>();
        for (int i = 0; i < wire.size(); i++) {
            firstOffset.merge(wire.get(i).id(), sent.get(i).offset(), Math::min);
            partitionOf.put(wire.get(i).id(), sent.get(i).partition());
        }
        int inversions = inversions(distinct, firstOffset);
        Map<Cell, Set<Integer>> cellPartitions = new HashMap<>();
        for (Event e : distinct) {
            cellPartitions.computeIfAbsent(e.cell(), c -> new HashSet<>()).add(partitionOf.get(e.id()));
        }
        long crossPartitionCells = cellPartitions.values().stream().filter(s -> s.size() >= 2).count();
        log.info("PROOF seed={} distinct={} duplicates={} produced={} partitions={}", seed, DISTINCT, DUPLICATES,
                wire.size(), describe(end));
        log.info("PROOF reordering: inversions={} (bound >0) crossPartitionCells={}/{} (bound >0)",
                inversions, crossPartitionCells, expected.size());
        assertThat(inversions).isGreaterThan(0);
        assertThat(crossPartitionCells).isGreaterThan(0);

        // --- phase 1: first consumption ------------------------------------------------------------------
        long t1 = System.nanoTime();
        awaitConsumed(end, DISTINCT + DUPLICATES);
        long consumeMs = millisSince(t1);

        Map<Cell, Integer> before = projectionMap();
        List<String> mismatchesBefore = mismatches(expected, before);
        long appliedBefore = count(results, ApplyResult.APPLIED);
        long duplicateBefore = count(results, ApplyResult.DUPLICATE);
        long dlt = dltRecords();
        log.info("PROOF before-replay: cells={} expectedSum={} actualSum={} mismatches={} applied={} (expect {}) "
                        + "duplicate={} (expect {}) dlt={} {}", expected.size(), sum(expected), sum(before),
                mismatchesBefore.size(), appliedBefore, DISTINCT, duplicateBefore, DUPLICATES, dlt,
                mismatchesBefore.isEmpty() ? "" : "first=" + mismatchesBefore.subList(0, Math.min(5, mismatchesBefore.size())));
        assertThat(mismatchesBefore).as("projection vs expected before replay").isEmpty();
        assertThat(results).hasSize(DISTINCT + DUPLICATES);
        assertThat(appliedBefore).isEqualTo(DISTINCT);
        assertThat(duplicateBefore).isEqualTo(DUPLICATES);
        assertThat(processedEvents()).isEqualTo(DISTINCT);
        assertThat(dlt).isZero();

        // --- phase 2: rewind the group to earliest -------------------------------------------------------
        long t2 = System.nanoTime();
        MessageListenerContainer container = listenerRegistry.getListenerContainers().iterator().next();
        container.stop();
        Map<TopicPartition, OffsetAndMetadata> rewind = earliest.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, en -> new OffsetAndMetadata(en.getValue())));
        // Retried: the group must be empty (the stopped member's leave has to land) before offsets can change.
        await().atMost(PHASE_WAIT).ignoreExceptions().untilAsserted(() ->
                admin.alterConsumerGroupOffsets(ProjectionService.CONSUMER, rewind).all().get(10, TimeUnit.SECONDS));
        Map<TopicPartition, Long> committedAfterRewind = committed();
        for (TopicPartition tp : partitions) {
            assertThat(committedAfterRewind.get(tp)).as("committed %s after rewind", tp).isEqualTo(earliest.get(tp));
        }
        int replayStart = results.size();
        container.start();

        // --- phase 3: replay everything ------------------------------------------------------------------
        awaitConsumed(end, 2 * (DISTINCT + DUPLICATES));
        long replayMs = millisSince(t2);

        Map<Cell, Integer> after = projectionMap();
        List<ApplyResult> replay = results.subList(replayStart, results.size());
        List<String> mismatchesAfter = mismatches(expected, after);
        int processed = processedEvents();
        int processedDistinct = jdbc.queryForObject(
                "SELECT count(DISTINCT event_id) FROM processed_events WHERE consumer = ?", Integer.class,
                ProjectionService.CONSUMER);
        List<UUID> repeatedIds = jdbc.queryForList(
                "SELECT event_id FROM processed_events GROUP BY event_id HAVING count(*) > 1", UUID.class);
        Set<UUID> ledgerIds = new HashSet<>(jdbc.queryForList(
                "SELECT event_id FROM processed_events WHERE consumer = ?", UUID.class, ProjectionService.CONSUMER));
        log.info("PROOF after-replay: mismatches={} unchangedVsBefore={} processed_events={} (expect {}) distinct={} "
                        + "repeatedIds={} replayApplied={} (expect 0) replayDuplicate={} (expect {})",
                mismatchesAfter.size(), after.equals(before), processed, DISTINCT, processedDistinct,
                repeatedIds.size(), count(replay, ApplyResult.APPLIED), count(replay, ApplyResult.DUPLICATE),
                DISTINCT + DUPLICATES);

        Duration total = Duration.ofNanos(System.nanoTime() - CLASS_START);
        log.info("PROOF timing: produce={}ms consume={}ms replay={}ms total={}s (bound <{}s, measured from class load)",
                produceMs, consumeMs, replayMs, String.format("%.1f", total.toMillis() / 1000.0), CI_BOUND.toSeconds());

        assertThat(mismatchesAfter).as("projection vs expected after replay").isEmpty();
        assertThat(after).isEqualTo(before);
        assertThat(processed).isEqualTo(DISTINCT);
        assertThat(processedDistinct).isEqualTo(DISTINCT);
        assertThat(repeatedIds).isEmpty();
        assertThat(ledgerIds).isEqualTo(distinct.stream().map(Event::id).collect(Collectors.toSet()));
        assertThat(replay).hasSize(DISTINCT + DUPLICATES).containsOnly(ApplyResult.DUPLICATE);
        assertThat(dltRecords()).isZero();
        assertThat(total).isLessThan(CI_BOUND);
    }

    // --- generation -----------------------------------------------------------------------------------------

    /**
     * Each of the 500 events lands in a random (user, habit, date) slot; within a slot the types alternate
     * completed, undone, completed... like a real toggle history, so every undone follows a completed.
     */
    private List<Event> generate(Random random) throws Exception {
        List<long[]> slots = new ArrayList<>(); // {user, habit, dateIndex}
        for (int u = 0; u < USERS; u++) {
            long user = FIRST_USER + u;
            for (int h = 1; h <= HABITS_PER_USER; h++) {
                for (int d = 0; d < DATES; d++) {
                    slots.add(new long[] {user, user * 10 + h, d});
                }
            }
        }
        // Every user and date appears at least once: the first 60 events cover each (user, date) via habit 1.
        List<Event> events = new ArrayList<>(DISTINCT);
        Map<String, Integer> seq = new HashMap<>();
        for (int i = 0; i < DISTINCT; i++) {
            long[] slot = i < USERS * DATES
                    ? slots.get((i / DATES) * HABITS_PER_USER * DATES + (i % DATES))
                    : slots.get(random.nextInt(slots.size()));
            long user = slot[0];
            long habit = slot[1];
            LocalDate date = FIRST_DATE.plusDays(slot[2]);
            int n = seq.merge(habit + "/" + date, 1, Integer::sum) - 1;
            String type = n % 2 == 0 ? HABIT_COMPLETED : HABIT_COMPLETION_UNDONE;
            UUID id = new UUID(random.nextLong(), random.nextLong()); // seeded, so a rerun has the same ids
            events.add(new Event(id, type, user, habit, date, n, envelope(id, type, user, habit, date)));
        }
        return events;
    }

    /** Undones whose first copy sits at a lower offset than the completed they follow (same slot = same key = same partition). */
    private static int inversions(List<Event> distinct, Map<UUID, Long> firstOffset) {
        Map<String, Map<Integer, Event>> bySlot = new HashMap<>();
        for (Event e : distinct) {
            bySlot.computeIfAbsent(e.slot(), s -> new HashMap<>()).put(e.seq(), e);
        }
        int inversions = 0;
        for (Event e : distinct) {
            if (HABIT_COMPLETION_UNDONE.equals(e.type())) {
                Event prev = bySlot.get(e.slot()).get(e.seq() - 1);
                if (firstOffset.get(e.id()) < firstOffset.get(prev.id())) {
                    inversions++;
                }
            }
        }
        return inversions;
    }

    /** Fixture-shaped (docs/events/habit.*.v1.json) envelope JSON; serialized once, so duplicates are the same bytes. */
    private String envelope(UUID id, String type, long user, long habit, LocalDate date) throws Exception {
        ObjectNode payload = json.createObjectNode()
                .put("habitId", habit)
                .put("userId", user)
                .put("habitName", "Habit " + habit)
                .put("completionDate", date.toString());
        ObjectNode root = json.createObjectNode()
                .put("event_id", id.toString())
                .put("event_type", type)
                .put("event_version", 1)
                .put("occurred_at", Instant.parse("2026-11-21T00:00:00Z").toString())
                .put("aggregate_id", Long.toString(habit));
        root.set("payload", payload);
        return json.writeValueAsString(root);
    }

    /** As OutboxPublisher sends: key = aggregate id, three headers, plain JSON text. */
    private static ProducerRecord<String, String> record(Event e) {
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaConfig.HABIT_EVENTS_TOPIC, e.key(), e.value());
        record.headers()
                .add("event_type", e.type().getBytes(StandardCharsets.UTF_8))
                .add("event_version", "1".getBytes(StandardCharsets.UTF_8))
                .add("event_id", e.id().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    // --- Kafka reads ----------------------------------------------------------------------------------------

    private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec) throws Exception {
        return admin.listOffsets(partitions.stream().collect(Collectors.toMap(Function.identity(), tp -> spec)))
                .all().get(10, TimeUnit.SECONDS).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, en -> en.getValue().offset()));
    }

    private Map<TopicPartition, Long> committed() throws Exception {
        return admin.listConsumerGroupOffsets(ProjectionService.CONSUMER).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).entrySet().stream()
                .filter(en -> en.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, en -> en.getValue().offset()));
    }

    /** Committed == log end on every partition (BATCH acks trail processing) and apply() seen {@code calls} times. */
    private void awaitConsumed(Map<TopicPartition, Long> end, int calls) {
        await().atMost(PHASE_WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
            Map<TopicPartition, Long> committed = committed();
            return results.size() >= calls
                    && end.entrySet().stream().allMatch(en -> en.getValue().equals(committed.get(en.getKey())));
        });
    }

    private long dltRecords() throws Exception {
        List<TopicPartition> dlt = IntStream.range(0, KafkaConfig.DLT_PARTITIONS)
                .mapToObj(p -> new TopicPartition(KafkaConfig.DLT_TOPIC, p)).toList();
        return offsets(dlt, OffsetSpec.latest()).values().stream().mapToLong(Long::longValue).sum();
    }

    // --- DB reads and comparison ----------------------------------------------------------------------------

    private Map<Cell, Integer> projectionMap() {
        Map<Cell, Integer> map = new TreeMap<>();
        jdbc.query("SELECT user_id, completion_date, completed_count FROM daily_completion_counts",
                rs -> {
                    map.put(new Cell(rs.getLong(1), rs.getObject(2, LocalDate.class)), rs.getInt(3));
                });
        return map;
    }

    private int processedEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ?", Integer.class,
                ProjectionService.CONSUMER);
    }

    /** Exact comparison: a missing row equals only an expected 0; any key not in expected is a mismatch. */
    private static List<String> mismatches(Map<Cell, Integer> expected, Map<Cell, Integer> actual) {
        List<String> out = new ArrayList<>();
        Set<Cell> keys = new HashSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (Cell c : new TreeSet<>(keys)) {
            Integer exp = expected.get(c);
            int act = actual.getOrDefault(c, 0);
            if (exp == null) {
                out.add(c + " unexpected=" + act);
            } else if (exp != act) {
                out.add(c + " expected=" + exp + " actual=" + act);
            }
        }
        return out;
    }

    private static int sum(Map<Cell, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static long count(List<ApplyResult> list, ApplyResult r) {
        return list.stream().filter(r::equals).count();
    }

    private static String describe(Map<TopicPartition, Long> end) {
        return new TreeMap<>(end.entrySet().stream()
                .collect(Collectors.toMap(en -> "p" + en.getKey().partition(), Map.Entry::getValue))).toString();
    }

    private static long millisSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
