package com.habitflow.habits;

import com.habitflow.habits.config.KafkaConfig;
import com.habitflow.habits.outbox.OutboxPublisher;
import com.habitflow.habits.outbox.OutboxRow;
import com.habitflow.habits.outbox.OutboxStore;
import com.habitflow.habits.outbox.OutboxWriter;
import com.habitflow.habits.support.TestcontainersOnly;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * R1-06b / HF-12 AC: two threads calling {@code publishBatch()} against 200 rows → exactly 200 records and
 * 200 sent_at, with no duplicates.
 *
 * <p>Concurrency is real, not two sequential loops: two worker threads, each calling the proxied
 * {@code publishBatch()} (its own transaction on its own pooled connection). A gate on each thread's first
 * {@code claim} holds both threads after their SELECT … FOR UPDATE SKIP LOCKED returned and before either
 * sends or commits, so both hold row locks at the same time. While they are held, the test thread counts from
 * a third connection how many unsent rows it can still lock: 200 − that = rows locked by the two publishers.
 *
 * <p>Same container/guard shape as {@link OutboxPublisherIT}: own Postgres + Kafka per class, the
 * Testcontainers-only guard, the poll loop off, and {@code @DirtiesContext} so the context (cached under a
 * key unique to this class because of the @SpyBean) never outlives its containers.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=false",
        // Not the default 50: proves the property reaches claim(), and gives 8 claim rounds instead of 4.
        "outbox.batch-size=" + OutboxConcurrencyIT.BATCH
})
@DirtiesContext
class OutboxConcurrencyIT {

    static final int ROWS = 200;
    static final int THREADS = 2;
    static final int BATCH = 25;
    private static final long GATE_SECONDS = 15;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

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

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxWriter outboxWriter;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("taskScheduler") TaskScheduler taskScheduler;
    // Not used directly: if GuardConfig stops being picked up, this injection fails the context.
    @Autowired TestcontainersOnly.Guard testcontainersOnlyGuard;

    @SpyBean OutboxStore outboxStore;

    @BeforeEach
    void clean() {
        reset(spiedStore());
        jdbc.execute("TRUNCATE habit_completions, outbox_events, habits RESTART IDENTITY CASCADE");
    }

    /** One claim as seen by the spy: which worker made it, the limit it passed, and the ids it locked. */
    private record Claim(String thread, int limit, List<UUID> ids) {
    }

    @Test
    @Timeout(120)
    void twoConcurrentPublishers_200Rows_exactly200RecordsAnd200SentAt_noDuplicates() throws Exception {
        Set<UUID> written = new LinkedHashSet<>();
        for (int i = 0; i < ROWS; i++) {
            String aggregateId = String.valueOf(1 + i % 10);
            written.add(tx.execute(s -> outboxWriter.write("habit", aggregateId, "habit.completed",
                    Map.of("habitId", Integer.parseInt(aggregateId), "userId", 7))).getId());
        }
        assertThat(written).hasSize(ROWS);

        // Overlap gate: each worker's FIRST claim counts down `arrived`, then waits for `release`.
        CountDownLatch arrived = new CountDownLatch(THREADS);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> gated = ConcurrentHashMap.newKeySet();
        List<Claim> claims = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<OutboxRow> rows = (List<OutboxRow>) invocation.callRealMethod();
            String thread = Thread.currentThread().getName();
            claims.add(new Claim(thread, invocation.getArgument(0), rows.stream().map(OutboxRow::id).toList()));
            if (thread.startsWith("publisher-") && gated.add(thread)) {
                arrived.countDown();
                // Timed so a mutation (e.g. a blocking FOR UPDATE) fails an assertion instead of hanging.
                release.await(GATE_SECONDS, TimeUnit.SECONDS);
            }
            return rows;
        }).when(spiedStore()).claim(anyInt());

        AtomicInteger seq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS,
                r -> new Thread(r, "publisher-" + (char) ('A' + seq.getAndIncrement())));
        CyclicBarrier start = new CyclicBarrier(THREADS);
        List<Future<Integer>> results = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    start.await(GATE_SECONDS, TimeUnit.SECONDS);
                    int sent = 0;
                    for (int n; (n = publisher.publishBatch()) > 0; ) {
                        sent += n;
                    }
                    return sent;
                }));
            }

            boolean overlap = arrived.await(GATE_SECONDS, TimeUnit.SECONDS);
            // Third connection, own transaction: rows the two publishers hold are skipped; rolled back after.
            int lockableNow = tx.execute(s -> {
                s.setRollbackOnly();
                return jdbc.queryForObject("SELECT count(*) FROM (SELECT id FROM outbox_events "
                        + "WHERE sent_at IS NULL FOR UPDATE SKIP LOCKED) x", Integer.class);
            });
            int lockedDuringOverlap = ROWS - lockableNow;
            release.countDown();

            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("publishers finished").isTrue();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            int returns = 0;
            for (Future<Integer> f : results) {
                returns += f.get();
            }

            verifyAndReport(written, claims, overlap, lockedDuringOverlap, returns, elapsedMs);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void dedicatedSchedulerPool_isSingleThreaded() throws Exception {
        assertThat(taskScheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) taskScheduler;
        // getPoolSize() is the live thread count (0 until something runs); the configured size is the core size.
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("outbox-scheduler-");
        String thread = scheduler.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
        assertThat(thread).isEqualTo("outbox-scheduler-1");
    }

    private void verifyAndReport(Set<UUID> written, List<Claim> claims, boolean overlap, int lockedDuringOverlap,
                                 int returns, long elapsedMs) {
        Map<String, Set<UUID>> byThread = new HashMap<>();
        Map<String, Integer> batchesByThread = new HashMap<>();
        Set<Integer> limits = new HashSet<>();
        for (Claim c : claims) {
            limits.add(c.limit());
            byThread.computeIfAbsent(c.thread(), k -> new HashSet<>()).addAll(c.ids());
            if (!c.ids().isEmpty()) {
                batchesByThread.merge(c.thread(), 1, Integer::sum);
            }
        }
        Set<UUID> claimedA = byThread.getOrDefault("publisher-A", Set.of());
        Set<UUID> claimedB = byThread.getOrDefault("publisher-B", Set.of());
        Set<UUID> intersection = new HashSet<>(claimedA);
        intersection.retainAll(claimedB);
        Set<UUID> union = new HashSet<>(claimedA);
        union.addAll(claimedB);

        List<UUID> onWire = readOurRecords(written);
        Set<UUID> distinct = new HashSet<>(onWire);
        Set<UUID> missing = new HashSet<>(written);
        missing.removeAll(distinct);
        int duplicates = onWire.size() - distinct.size();

        Map<String, Object> db = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE sent_at IS NOT NULL) AS sent,
                       count(*) FILTER (WHERE sent_at IS NULL)     AS unsent,
                       count(*) FILTER (WHERE attempts <> 0)       AS attempts_nonzero,
                       round(extract(epoch FROM max(sent_at) - min(sent_at)) * 1000) AS sent_span_ms
                FROM outbox_events
                """);
        long sent = ((Number) db.get("sent")).longValue();

        System.out.printf("CONCURRENCY rows=%d threads=%d batch=%d limits=%s overlap=%s lockedDuringOverlap=%d/%d "
                        + "claimedA=%d claimedB=%d batchesA=%d batchesB=%d intersection=%d records=%d/%d "
                        + "distinct=%d/%d missing=%d duplicates=%d/0 sent_at=%d/%d unsent=%s returns=%d/%d "
                        + "attempts_nonzero=%s sentSpanMs(DB)=%s elapsedMs(JVM)=%d%n",
                written.size(), THREADS, BATCH, limits, overlap, lockedDuringOverlap, THREADS * BATCH,
                claimedA.size(), claimedB.size(), batchesByThread.getOrDefault("publisher-A", 0),
                batchesByThread.getOrDefault("publisher-B", 0), intersection.size(), onWire.size(), ROWS,
                distinct.size(), ROWS, missing.size(), duplicates, sent, ROWS, db.get("unsent"), returns, ROWS,
                db.get("attempts_nonzero"), db.get("sent_span_ms"), elapsedMs);

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(overlap).as("1. both publishers held their first claim at the same time").isTrue();
        soft.assertThat(lockedDuringOverlap).as("2. rows locked by the two publishers during the overlap")
                .isEqualTo(THREADS * BATCH);
        soft.assertThat(intersection).as("3. rows claimed by both threads").isEmpty();
        soft.assertThat(union).as("3. claims cover every row").isEqualTo(written);
        soft.assertThat(claimedA).as("3. publisher-A claimed rows").isNotEmpty();
        soft.assertThat(claimedB).as("3. publisher-B claimed rows").isNotEmpty();
        soft.assertThat(limits).as("3. outbox.batch-size reached claim()").containsExactly(BATCH);
        soft.assertThat(missing).as("4. event_ids never consumed").isEmpty();
        soft.assertThat(onWire).as("5. records on habit-events for these rows").hasSize(ROWS);
        soft.assertThat(distinct).as("6. distinct event_ids on the wire").hasSize(ROWS);
        soft.assertThat(duplicates).as("6. duplicate records on the wire").isZero();
        soft.assertThat(sent).as("7. rows with sent_at").isEqualTo(ROWS);
        soft.assertThat(((Number) db.get("unsent")).longValue()).as("7. rows without sent_at").isZero();
        soft.assertThat(((Number) db.get("attempts_nonzero")).longValue()).as("7. rows with attempts > 0").isZero();
        soft.assertThat(returns).as("8. sum of publishBatch() returns").isEqualTo(ROWS);
        soft.assertAll();
    }

    /** Reads habit-events from the beginning up to its current end offsets; event_ids of our records, in order. */
    private List<UUID> readOurRecords(Set<UUID> ours) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-concurrency-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<UUID> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(KafkaConfig.HABIT_EVENTS_TOPIC).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).collect(Collectors.toList());
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                assertThat(System.nanoTime()).as("read habit-events to its end offsets within 30 s").isLessThan(deadline);
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
                    Header h = r.headers().lastHeader("event_id");
                    if (h != null) {
                        UUID id = UUID.fromString(new String(h.value(), StandardCharsets.UTF_8));
                        if (ours.contains(id)) {
                            found.add(id);
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableList(found);
    }

    /** The spy behind OutboxStore's @Transactional(MANDATORY) proxy: stub it, not the proxy. */
    private OutboxStore spiedStore() {
        return AopTestUtils.getUltimateTargetObject(outboxStore);
    }
}
