package com.habitflow.insights;

import com.habitflow.insights.support.TestcontainersOnly;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for insights-service integration tests: a real Postgres via Testcontainers, guarded by
 * {@link TestcontainersOnly.Guard}.
 *
 * <p>The datasource comes from {@code @DynamicPropertySource} (not {@code @ServiceConnection}) so the
 * Environment holds the container URL and the guard can check it before Hikari or Flyway exist.
 * No Kafka container yet: nothing consumes or produces until HF-18, so bootstrap-servers points at a
 * closed port and topic creation is off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false"
})
@Testcontainers
// Explicit: a nested @TestConfiguration is only detected on the test class itself, not on a superclass.
@Import(IntegrationTestBase.GuardConfig.class)
public abstract class IntegrationTestBase {

    /**
     * Singleton container (HF-17): started once per JVM, never stopped by JUnit (Ryuk removes it at exit).
     * Not {@code @Container}: that lifecycle stops it after each class while Spring's context cache keeps
     * the next class's identical context pointed at the stopped container's port. {@code @Testcontainers}
     * stays on the class for subclasses' own {@code @Container} fields (InsightsTopicGuardIT's Kafka).
     */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class GuardConfig {
        @Bean
        static TestcontainersOnly.Guard testcontainersOnlyGuard() {
            return new TestcontainersOnly.Guard(postgres::getJdbcUrl);
        }
    }

    // Not used directly: if GuardConfig stops being picked up, this injection fails the context.
    @Autowired
    TestcontainersOnly.Guard testcontainersOnlyGuard;
}
