package com.habitflow.habits;

import com.habitflow.habits.config.KafkaConfig;
import com.habitflow.habits.dto.HabitRequest;
import com.habitflow.habits.dto.HabitResponse;
import com.habitflow.habits.service.HabitService;
import com.habitflow.habits.support.TestcontainersOnly;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proof #2 (R1-15 / HF-23, proves L1, L2, L3): writes made while the broker is unavailable succeed quickly,
 * are retried with backoff, and are all published once the broker is back.
 *
 * <p>The broker is taken away with {@code docker pause}, not stop/start: a restarted container gets a new
 * mapped port and the producer could never reconnect. The pause is kept under 15 s (asserted) so the
 * embedded ZooKeeper (cp-kafka 7.5, legacy mode, ~18 s session timeout) doesn't expire the broker's session.
 *
 * <p>Every time check uses the database clock ({@code clock_timestamp()}); Awaitility only bounds the waits.
 * The publisher's poll loop is on: recovery by the real scheduler is the thing being proven. Producer
 * timeouts and the backoff cap are shortened through this test's properties only.
 *
 * <p>{@code @Testcontainers} is declared before {@code @SpringBootTest} so its afterAll runs last, and
 * {@code @DirtiesContext} closes this context (stopping the scheduler) before the containers stop.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=true",
        "outbox.poll-ms=100",
        "outbox.send-timeout-ms=3000",
        "outbox.backoff.max-ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        // Must be >= linger.ms + request.timeout.ms, or the producer refuses to start.
        "spring.kafka.producer.properties.delivery.timeout.ms=2000",
        "spring.kafka.producer.properties.max.block.ms=1000"
})
@DirtiesContext
class BrokerOutageProofIT {

    private static final Logger log = LoggerFactory.getLogger(BrokerOutageProofIT.class);

    private static final int HABITS = 25; // 25 creates + 25 toggles = 50 operations, 50 outbox rows
    private static final long USER_ID = 42L;
    private static final long MAX_OP_MS = 1_000;
    private static final long MAX_PAUSE_MS = 15_000;
    private static final Duration DELIVERY_WINDOW = Duration.ofSeconds(60);

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

    @Autowired HabitService habitService;
    @Autowired JdbcTemplate jdbc;
    // Not used directly: if GuardConfig stops being picked up, this injection fails the context.
    @Autowired TestcontainersOnly.Guard testcontainersOnlyGuard;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE habit_completions, outbox_events, habits RESTART IDENTITY CASCADE");
    }

    @Test
    void writesDuringBrokerPause_areFastAndAllDeliveredAfterUnpause() {
        // 0. Warm-up: the producer exists, has the topic's metadata, and both write paths have run once.
        HabitResponse warm = habitService.createHabit(new HabitRequest("Warm-up", "before the pause", "DAILY"), USER_ID);
        habitService.toggleCompletion(warm.getId(), USER_ID);
        List<UUID> warmUpIds = jdbc.queryForList("SELECT id FROM outbox_events", UUID.class);
        assertThat(warmUpIds).hasSize(2);
        await("warm-up rows published").atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> unsent() == 0);

        String containerId = kafka.getContainerId();
        Instant tPause;
        Instant tWritesDone;
        Instant tAllFailed;
        Instant tUnpause;
        long maxOpMs = 0;
        // tPause and tUnpause are read after each docker command returns. The broker was already frozen
        // before tPause was read, so the measured pause can be slightly short (the error is one command
        // round-trip plus one SELECT, milliseconds against the 15 s budget).
        Throwable inFlight = null;
        try {
            // 1. Pause the broker. Inside the try, so finally always unpauses, even if this call or dbNow() fails.
            kafka.getDockerClient().pauseContainerCmd(containerId).exec();
            tPause = dbNow();

            // 2. 50 operations through HabitService, each timed on the DB clock (the SELECTs count against it).
            for (int i = 0; i < HABITS; i++) {
                Instant t0 = dbNow();
                HabitResponse h = habitService.createHabit(new HabitRequest("Habit " + i, "during outage", "DAILY"), USER_ID);
                Instant t1 = dbNow();
                habitService.toggleCompletion(h.getId(), USER_ID);
                Instant t2 = dbNow();
                maxOpMs = Math.max(maxOpMs, Math.max(millis(t0, t1), millis(t1, t2)));
            }
            tWritesDone = dbNow();

            // 3. Every outage row has been tried and failed at least once: unsent, attempts >= 1, last_error set.
            // 12 s keeps the pause inside MAX_PAUSE_MS (asserted below) if the publisher is slow.
            await("all 50 outage rows failed at least once").atMost(Duration.ofSeconds(12))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> jdbc.queryForObject("""
                            SELECT count(*) FROM outbox_events
                            WHERE sent_at IS NULL AND attempts >= 1 AND last_error IS NOT NULL AND id <> ALL(?::uuid[])
                            """, Integer.class, (Object) uuidArray(warmUpIds)) == 2 * HABITS);
            tAllFailed = dbNow();
        } catch (Throwable t) {
            inFlight = t;
            throw t;
        } finally {
            // 4. Unpause. An unpause failure must not hide the failure that got us here: attach it to that one
            // as suppressed, and only throw it on its own when the try block succeeded.
            try {
                kafka.getDockerClient().unpauseContainerCmd(containerId).exec();
            } catch (RuntimeException unpauseFailure) {
                if (inFlight == null) {
                    throw unpauseFailure;
                }
                inFlight.addSuppressed(unpauseFailure);
            }
        }
        tUnpause = dbNow();
        // One 60 s delivery window from the unpause (DB clock), shared by the consume wait and the sent_at wait.
        Instant deliveryDeadline = tUnpause.plus(DELIVERY_WINDOW);

        assertThat(maxOpMs).as("slowest operation while paused (ms)").isLessThan(MAX_OP_MS);
        assertThat(millis(tPause, tUnpause)).as("pause length on the DB clock (ms)").isLessThan(MAX_PAUSE_MS);

        List<UUID> outageIds = jdbc.queryForList(
                "SELECT id FROM outbox_events WHERE id <> ALL(?::uuid[]) ORDER BY created_at", UUID.class,
                (Object) uuidArray(warmUpIds));
        assertThat(outageIds).hasSize(2 * HABITS);
        Map<Integer, Long> attemptsHistogram = jdbc.queryForList(
                        "SELECT attempts FROM outbox_events WHERE id <> ALL(?::uuid[])", Integer.class,
                        (Object) uuidArray(warmUpIds))
                .stream().collect(Collectors.groupingBy(a -> a, java.util.TreeMap::new, Collectors.counting()));
        String sampleError = jdbc.queryForObject(
                "SELECT last_error FROM outbox_events WHERE last_error IS NOT NULL LIMIT 1", String.class);
        log.info("HF-23 outage: attempts at unpause {} ; sample last_error: {}", attemptsHistogram, sampleError);

        // 5. Within 60 s of the unpause, all 50 event_ids are on habit-events ...
        Set<String> expected = outageIds.stream().map(UUID::toString).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        int recordsForOutageIds = consumeUntilAllSeen(expected, seen, remainingUntil(deliveryDeadline));

        // 6. ... and every row has sent_at (the publisher commits after the acks, so the rows may trail the records).
        await("all outage rows marked sent").atMost(remainingUntil(deliveryDeadline))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> unsent() == 0);
        Instant tAllDelivered = jdbc.queryForObject(
                "SELECT max(sent_at) FROM outbox_events WHERE id <> ALL(?::uuid[])", Timestamp.class,
                (Object) uuidArray(warmUpIds)).toInstant();

        assertThat(seen).as("event_ids consumed from habit-events").containsAll(expected);
        assertThat(millis(tUnpause, tAllDelivered)).as("unpause -> last sent_at on the DB clock (ms)")
                .isLessThan(DELIVERY_WINDOW.toMillis());

        int duplicates = recordsForOutageIds - seen.size();
        log.info("HF-23 phases (DB clock, ms): pause->writes done {} | writes done->all failed once {} | "
                        + "all failed once->unpause {} | unpause->all delivered {} | pause total {} | slowest op {}",
                millis(tPause, tWritesDone), millis(tWritesDone, tAllFailed), millis(tAllFailed, tUnpause),
                millis(tUnpause, tAllDelivered), millis(tPause, tUnpause), maxOpMs);
        log.info("HF-23 result: delivered {}/{}, duplicates {} ({} records for {} distinct event_ids)",
                seen.size(), expected.size(), duplicates, recordsForOutageIds, seen.size());
    }

    /** Polls habit-events from the start; returns how many records carried one of the expected event_ids. */
    private int consumeUntilAllSeen(Set<String> expected, Set<String> seen, Duration atMost) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "broker-outage-proof-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        int[] records = {0};
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaConfig.HABIT_EVENTS_TOPIC));
            await("all 50 event_ids consumed").atMost(atMost).pollInSameThread()
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> {
                        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
                            String id = header(r, "event_id");
                            if (expected.contains(id)) {
                                records[0]++;
                                seen.add(id);
                            }
                        }
                        return seen.containsAll(expected);
                    });
        }
        return records[0];
    }

    /**
     * Time left until {@code deadline}, on the DB clock. At least 1 ms, because Awaitility rejects a zero
     * timeout; a spent window then fails the next wait at once.
     */
    private Duration remainingUntil(Instant deadline) {
        Duration left = Duration.between(dbNow(), deadline);
        return left.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : left;
    }

    private int unsent() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE sent_at IS NULL", Integer.class);
    }

    private Instant dbNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private static long millis(Instant from, Instant to) {
        return Duration.between(from, to).toMillis();
    }

    private static String[] uuidArray(List<UUID> ids) {
        List<String> s = new ArrayList<>(ids.size());
        ids.forEach(id -> s.add(id.toString()));
        return s.toArray(String[]::new);
    }

    private static String header(ConsumerRecord<String, String> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
