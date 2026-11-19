package com.habitflow.auth;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests: real Postgres and Kafka via Testcontainers,
 * wired into Spring through {@code @ServiceConnection} (overrides
 * spring.datasource.* and spring.kafka.bootstrap-servers from application.yml).
 */
@SpringBootTest
@Testcontainers
public abstract class IntegrationTestBase {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    // Legacy org.testcontainers.containers.KafkaContainer (deprecated in 1.21): it is the
    // type Spring Boot 3.2's @ServiceConnection supports.
    @Container
    @ServiceConnection
    @SuppressWarnings("deprecation")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
}
