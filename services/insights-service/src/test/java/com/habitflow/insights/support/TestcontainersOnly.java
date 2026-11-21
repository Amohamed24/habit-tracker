package com.habitflow.insights.support;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Testcontainers-only rule for ITs (r1-plan.md, Conventions): refuse to boot unless the app will
 * migrate and query the Testcontainers database. On the dev machine localhost:5432 is a Homebrew
 * Postgres holding the monolith's habitflow DB, so a mis-wired IT must abort before Flyway runs.
 */
public final class TestcontainersOnly {

    static final int FORBIDDEN_PORT = 5432;
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

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
        if (isLocalPostgresDefault(datasourceUrl)) {
            throw refuse("spring.datasource.url [" + datasourceUrl + "] points at the local Postgres on port "
                    + FORBIDDEN_PORT);
        }
        String flywayUrl = env.getProperty("spring.flyway.url");
        if (flywayUrl != null) {
            throw refuse("spring.flyway.url is set to [" + flywayUrl
                    + "]; Flyway would bypass the checked datasource");
        }
    }

    /**
     * Exact host+port match on the parsed JDBC URL: host localhost or 127.0.0.1 with port 5432.
     * A URL without a port uses the Postgres default (5432), so it counts. A URL that can't be
     * parsed as a single host is refused too, since the check can't prove where it points.
     */
    static boolean isLocalPostgresDefault(String jdbcUrl) {
        URI uri;
        try {
            uri = new URI(jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring("jdbc:".length()) : jdbcUrl);
        } catch (URISyntaxException e) {
            throw refuse("spring.datasource.url [" + jdbcUrl + "] is not a parseable JDBC URL");
        }
        if (uri.getHost() == null) {
            throw refuse("spring.datasource.url [" + jdbcUrl + "] has no single parseable host");
        }
        int port = uri.getPort() == -1 ? FORBIDDEN_PORT : uri.getPort();
        return LOCAL_HOSTS.contains(uri.getHost()) && port == FORBIDDEN_PORT;
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
