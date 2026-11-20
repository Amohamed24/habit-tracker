package com.habitflow.habits.support;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestcontainersOnlyTest {

    private static final String CONTAINER_URL = "jdbc:postgresql://localhost:55001/test";

    @Test
    void matchingContainerUrl_passes() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.url", CONTAINER_URL);

        assertThatCode(() -> TestcontainersOnly.assertTestcontainersOnly(env, CONTAINER_URL)).doesNotThrowAnyException();
    }

    @Test
    void differentUrl_refuses() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/habitflow");

        assertThatThrownBy(() -> TestcontainersOnly.assertTestcontainersOnly(env, CONTAINER_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Testcontainers-only guard")
                .hasMessageContaining("localhost:5432/habitflow");
    }

    @Test
    void localhost5432_refusedEvenWhenExpected() {
        String url = "jdbc:postgresql://localhost:5432/habitflow";
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.url", url);

        assertThatThrownBy(() -> TestcontainersOnly.assertTestcontainersOnly(env, url))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("points at localhost:5432");
    }

    @Test
    void flywayUrlSet_refuses() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", CONTAINER_URL)
                .withProperty("spring.flyway.url", "jdbc:postgresql://localhost:5432/habitflow");

        assertThatThrownBy(() -> TestcontainersOnly.assertTestcontainersOnly(env, CONTAINER_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.url is set");
    }
}
