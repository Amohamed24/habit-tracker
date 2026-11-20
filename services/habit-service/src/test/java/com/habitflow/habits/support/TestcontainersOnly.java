package com.habitflow.habits.support;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import java.util.function.Supplier;

/**
 * Testcontainers-only rule for ITs (r1-plan.md, Conventions): refuse to boot unless the app will
 * migrate and query the Testcontainers database. On the dev machine localhost:5432 is a Homebrew
 * Postgres holding the monolith's habitflow DB, so a mis-wired IT must abort before Flyway runs.
 */
public final class TestcontainersOnly {

    static final String FORBIDDEN_HOST_PORT = "localhost:5432";

    private TestcontainersOnly() {
    }

    /**
     * Throws unless the resolved spring.datasource.url is exactly {@code expectedUrl}, does not point
     * at localhost:5432, and spring.flyway.url is unset (Flyway would otherwise open its own
     * connection, and the datasource check would prove nothing about where migrations run).
     */
    public static void assertTestcontainersOnly(Environment env, String expectedUrl) {
        String datasourceUrl = env.getProperty("spring.datasource.url");
        if (expectedUrl == null || !expectedUrl.equals(datasourceUrl)) {
            throw refuse("spring.datasource.url resolved to [" + datasourceUrl
                    + "] but this test may only use the Testcontainers database [" + expectedUrl + "]");
        }
        if (datasourceUrl.contains(FORBIDDEN_HOST_PORT)) {
            throw refuse("spring.datasource.url [" + datasourceUrl + "] points at " + FORBIDDEN_HOST_PORT);
        }
        String flywayUrl = env.getProperty("spring.flyway.url");
        if (flywayUrl != null) {
            throw refuse("spring.flyway.url is set to [" + flywayUrl
                    + "]; Flyway would bypass the checked datasource");
        }
    }

    private static IllegalStateException refuse(String reason) {
        return new IllegalStateException("Testcontainers-only guard: refusing to boot, " + reason);
    }

    /**
     * Runs the check as a BeanFactoryPostProcessor: after every property source is in place
     * (application.yml, @DynamicPropertySource, command-line args) and before any regular bean
     * exists, so no Hikari pool, no Flyway and no JPA have touched a database yet.
     * Register it from a {@code static @Bean} method so it doesn't force its config class early.
     */
    public static final class Guard implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {
        private final Supplier<String> expectedUrl;
        private Environment environment;

        public Guard(Supplier<String> expectedUrl) {
            this.expectedUrl = expectedUrl;
        }

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            assertTestcontainersOnly(environment, expectedUrl.get());
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
