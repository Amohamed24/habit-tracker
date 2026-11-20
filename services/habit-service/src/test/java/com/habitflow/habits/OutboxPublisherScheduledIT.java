package com.habitflow.habits;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The poll loop is wired: with the publisher on (the production default), a habit created through
 * the service reaches habit-events and its outbox row gets sent_at, with no direct publishBatch() call.
 *
 * <p>{@code @Testcontainers} is declared before {@code @SpringBootTest} so its afterAll runs last, and
 * {@code @DirtiesContext} closes this context (stopping the scheduler) before the containers stop.
 * Otherwise the cached context would keep polling a dead Postgres for the rest of the failsafe run.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.publisher.enabled=true",
        "outbox.poll-ms=100"
})
@DirtiesContext
class OutboxPublisherScheduledIT {

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
    @Autowired ObjectMapper objectMapper;
    // Not used directly: if GuardConfig stops being picked up, this injection fails the context.
    @Autowired TestcontainersOnly.Guard testcontainersOnlyGuard;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE habit_completions, outbox_events, habits RESTART IDENTITY CASCADE");
    }

    @Test
    void scheduledPoll_publishesCreatedHabit() throws Exception {
        HabitResponse created = habitService.createHabit(new HabitRequest("Read 20 pages", "before bed", "DAILY"), 7L);
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events", UUID.class);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, eventId));

        ConsumerRecord<String, String> record = awaitRecord(eventId);
        assertThat(record.key()).isEqualTo(created.getId().toString());
        assertThat(header(record, "event_type")).isEqualTo("habit.created");
        assertThat(header(record, "event_version")).isEqualTo("1");
        assertThat(objectMapper.readTree(record.value()).get("event_id").asText()).isEqualTo(eventId.toString());
    }

    private ConsumerRecord<String, String> awaitRecord(UUID eventId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-scheduled-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaConfig.HABIT_EVENTS_TOPIC));
            await().atMost(Duration.ofSeconds(30)).pollInSameThread().pollInterval(Duration.ofMillis(50))
                    .until(() -> {
                        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(250))) {
                            if (eventId.toString().equals(header(r, "event_id"))) {
                                found.set(r);
                            }
                        }
                        return found.get() != null;
                    });
        }
        return found.get();
    }

    private static String header(ConsumerRecord<String, String> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
