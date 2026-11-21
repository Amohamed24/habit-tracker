package com.habitflow.insights;

import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;

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
        // No migrations until HF-17: nothing applied, nothing pending, and the app still booted.
        assertThat(flyway.info().applied()).isEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void actuatorHealth_isUp() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(response.getBody().path("components").path("db").path("status").asText()).isEqualTo("UP");
    }
}
