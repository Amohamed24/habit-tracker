package com.habitflow.insights;

import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load test for insights-service (R1-09a / HF-15). The Jira test plan names it
 * InsightsServiceApplicationTests; it is *IT because it needs a container, and *IT runs under failsafe only.
 */
class InsightsServiceApplicationIT extends IntegrationTestBase {

    @Autowired Environment environment;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired TestRestTemplate rest;

    @Test
    void contextLoads_onContainerDatabase() throws Exception {
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(postgres.getJdbcUrl());
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
        // Own database, so Flyway's default history table; same baseline settings as habit-service.
        assertThat(flyway.getConfiguration().getTable()).isEqualTo("flyway_schema_history");
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(flyway.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
        // HF-17: exactly V1 applied on this (empty) container database, no baseline row, nothing pending.
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(1);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getDescription()).isEqualTo("projection");
        assertThat(applied[0].getType()).isEqualTo(CoreMigrationType.SQL);
        assertThat(applied[0].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
        // ...and V1's tables are on the container database the URL checks above pinned.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('public.processed_events') IS NOT NULL,"
                     + " to_regclass('public.daily_completion_counts') IS NOT NULL")) {
            assertThat(c.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
            rs.next();
            assertThat(rs.getBoolean(1)).as("processed_events exists").isTrue();
            assertThat(rs.getBoolean(2)).as("daily_completion_counts exists").isTrue();
        }
    }

    @Test
    void actuatorHealth_isUp() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(response.getBody().path("components").path("db").path("status").asText()).isEqualTo("UP");
    }
}
