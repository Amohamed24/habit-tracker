package com.habitflow.insights;

import com.habitflow.insights.support.TestcontainersOnly;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link TestcontainersOnly.Guard} (as {@link IntegrationTestBase} registers it) actually fires,
 * and fires before Flyway. Without this a guard that never runs looks identical to one that works.
 *
 * <p>Named *GuardIT, not Test*IT: surefire's default includes match Test*.java, so it would run twice.
 *
 * <p>The "wrong" target is a canary database on the same container, never localhost:5432, so even a
 * broken guard can't touch a real database. "Never reached Flyway" = the canary has no
 * flyway_schema_history after the failed boot: Flyway 9 creates that table on every migrate, even with
 * zero migrations, which {@link #matchingUrl_boots()} proves.
 */
@Testcontainers
class InsightsContainerGuardIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final String CANARY = "guard_canary";

    /** The guard exactly as IntegrationTestBase registers it: expects the container's default database. */
    @Configuration(proxyBeanMethods = false)
    static class GuardConfig {
        @Bean
        static TestcontainersOnly.Guard testcontainersOnlyGuard() {
            return new TestcontainersOnly.Guard(postgres::getJdbcUrl);
        }
    }

    @BeforeAll
    static void createCanary() throws SQLException {
        try (Connection c = connect(postgres.getJdbcUrl()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + CANARY);
        }
    }

    @Test
    void wrongDatasourceUrl_abortsBeforeFlyway() throws SQLException {
        assertThatThrownBy(() -> boot("--spring.datasource.url=" + canaryUrl()))
                .hasStackTraceContaining("Testcontainers-only guard: refusing to boot")
                .hasStackTraceContaining(canaryUrl());

        assertThat(hasFlywayHistory(canaryUrl())).as("Flyway ran against the canary").isFalse();
    }

    @Test
    void flywayUrlSet_abortsBeforeFlyway() throws SQLException {
        assertThatThrownBy(() -> boot(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.flyway.url=" + canaryUrl(),
                "--spring.flyway.user=" + postgres.getUsername(),
                "--spring.flyway.password=" + postgres.getPassword()))
                .hasStackTraceContaining("spring.flyway.url is set");

        assertThat(hasFlywayHistory(canaryUrl())).as("Flyway ran against the canary").isFalse();
    }

    @Test
    void matchingUrl_boots() throws SQLException {
        boot("--spring.datasource.url=" + postgres.getJdbcUrl());

        // The control: a boot that does reach Flyway leaves its history table behind (0 migrations or not).
        assertThat(hasFlywayHistory(postgres.getJdbcUrl())).isTrue();
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Overrides go in as command-line args: SpringApplicationBuilder.properties() would lose to application.yml. */
    private static void boot(String... overrides) {
        String[] args = new String[overrides.length + 4];
        System.arraycopy(overrides, 0, args, 0, overrides.length);
        args[overrides.length] = "--spring.datasource.username=" + postgres.getUsername();
        args[overrides.length + 1] = "--spring.datasource.password=" + postgres.getPassword();
        // Closed port: no boot here may depend on (or reach) a local Kafka on 9092.
        args[overrides.length + 2] = "--spring.kafka.bootstrap-servers=localhost:1";
        args[overrides.length + 3] = "--spring.kafka.admin.auto-create=false";
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(
                InsightsServiceApplication.class, GuardConfig.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            // started
        }
    }

    private static boolean hasFlywayHistory(String url) throws SQLException {
        try (Connection c = connect(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('flyway_schema_history') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static String canaryUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + CANARY;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }
}
