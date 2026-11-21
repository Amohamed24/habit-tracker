package com.habitflow.insights;

import com.habitflow.insights.config.KafkaConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Topic guard proof (R1-09b / HF-16): on a fresh broker with auto-create on, starting insights-service alone
 * (habit-service never runs here) leaves {@code habit-events} with 3 partitions, not the broker default of 1.
 *
 * <p>Extends {@link IntegrationTestBase}, so the Testcontainers-only datasource guard still applies. The base
 * points Kafka at a closed port with topic creation off; the dynamic properties below override both for this
 * class only ({@code @DynamicPropertySource} beats {@code @SpringBootTest} properties).
 */
class InsightsTopicGuardIT extends IntegrationTestBase {

    // Legacy KafkaContainer, same image as habit-service's ITs and compose (cp-kafka 7.5.0). Fresh per class,
    // so no habit-events topic exists before this context starts.
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> "true");
    }

    @Autowired KafkaAdmin kafkaAdmin;

    @Test
    void control_brokerAutoCreatesUnknownTopicsWithOnePartition() throws Exception {
        try (AdminClient admin = admin()) {
            ConfigResource broker = new ConfigResource(ConfigResource.Type.BROKER, "1");
            Config config = admin.describeConfigs(List.of(broker)).all().get(30, TimeUnit.SECONDS).get(broker);
            assertThat(config.get("auto.create.topics.enable").value()).isEqualTo("true");
        }

        // What an early subscriber would do to habit-events without the guard.
        String unguarded = "topic-guard-control";
        assertThat(partitionsSeenByClient(unguarded)).as("auto-created partitions").isEqualTo(1);
        assertThat(describe(unguarded).partitions()).hasSize(1);
    }

    /**
     * One test on purpose: the container is shared by every test in this class, so a separate idempotency
     * test that re-creates habit-events could run first and mask a missing guard (a mutation run with the
     * bean removed passed that way). Here the first thing that touches habit-events is the describe.
     */
    @Test
    void insightsFirst_habitEventsHasThreePartitions_andHabitServiceDefinitionIsIdempotent() throws Exception {
        TopicDescription topic = describe(KafkaConfig.HABIT_EVENTS_TOPIC);

        assertThat(topic.partitions()).hasSize(3);
        assertThat(topic.partitions()).allSatisfy(p -> assertThat(p.replicas()).hasSize(1));
        // A client arriving after startup (the future HF-18 listener) sees the guarded topic, not a new one.
        assertThat(partitionsSeenByClient(KafkaConfig.HABIT_EVENTS_TOPIC)).isEqualTo(3);

        // Exactly habit-service's bean: what happens when habit-service starts second.
        assertThatCode(() -> kafkaAdmin.createOrModifyTopics(
                TopicBuilder.name("habit-events").partitions(3).replicas(1).build()))
                .doesNotThrowAnyException();
        assertThat(describe(KafkaConfig.HABIT_EVENTS_TOPIC).partitions()).hasSize(3);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    private static TopicDescription describe(String topic) throws Exception {
        try (AdminClient admin = admin()) {
            return admin.describeTopics(Set.of(topic)).allTopicNames().get(30, TimeUnit.SECONDS).get(topic);
        }
    }

    /** A plain consumer's metadata request, the same call that triggers broker auto-create. */
    private static int partitionsSeenByClient(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "true");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(30));
            return partitions.size();
        }
    }
}
