package com.habitflow.habits;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the V1 baseline in every starting state the habitflow database can be in.
 *
 * <p>Flyway runs at startup, so each scenario gets its own database, sets up its starting
 * state with plain JDBC, and then boots the real {@link HabitServiceApplication} against it.
 * Flyway and {@code ddl-auto: validate} therefore come straight from application.yml (no
 * copied config to drift), and a context that boots means Hibernate validation passed.
 * Kafka has nothing to do with the schema, so the listener and topic creation are switched off.
 */
@Testcontainers
class FlywayBaselineIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final String HISTORY = "flyway_schema_history_habit";

    // auth-service's Hibernate-built users table, as it exists on the compose volume.
    private static final String USERS_DDL = """
            CREATE TABLE users (
              id bigserial PRIMARY KEY, created_at timestamp(6), email varchar(255) NOT NULL,
              password varchar(255) NOT NULL, updated_at timestamp(6),
              CONSTRAINT uk_6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email));
            INSERT INTO users (email, password, created_at) VALUES
              ('a@example.com', 'x', now()), ('b@example.com', 'y', now());
            """;

    // The old habit-service schema exactly as Hibernate ddl-auto=update built it (bigserial keys,
    // generated constraint names), with rows.
    private static final String HIBERNATE_HABIT_DDL = """
            CREATE TABLE habits (
              id bigserial PRIMARY KEY, created_at timestamp(6), description varchar(255),
              frequency varchar(255) NOT NULL, name varchar(255) NOT NULL, updated_at timestamp(6),
              user_id bigint NOT NULL);
            CREATE TABLE habit_completions (
              id bigserial PRIMARY KEY, completion_date date NOT NULL, created_at timestamp(6),
              habit_id bigint NOT NULL,
              CONSTRAINT uk1jmmm9bn1u8tqv76tvux1htde UNIQUE (habit_id, completion_date),
              CONSTRAINT fkdt8vmlabmctme9yn5fbnp9ipf FOREIGN KEY (habit_id) REFERENCES habits(id));
            INSERT INTO habits (name, frequency, user_id, created_at) VALUES
              ('Read', 'DAILY', 1, now()), ('Run', 'WEEKLY', 2, now());
            INSERT INTO habit_completions (habit_id, completion_date, created_at) VALUES
              (1, '2026-11-01', now()), (1, '2026-11-02', now()), (2, '2026-11-03', now());
            """;

    @Test
    void a_emptyDatabase_runsV1AndBoots() throws Exception {
        String db = createDatabase("s_a");

        boot(db);

        assertThat(history(db)).containsExactly("1|SQL|true");
        assertHabitTablesWithOneUniqueAndOneFk(db, null, null);
    }

    @Test
    void b_usersCreatedFirst_stillCreatesHabitTablesAndLeavesUsersAlone() throws Exception {
        String db = createDatabase("s_b");
        exec(db, USERS_DDL);
        List<String> usersBefore = usersSnapshot(db);

        boot(db);

        assertThat(history(db)).containsExactly("0|BASELINE|true", "1|SQL|true");
        assertHabitTablesWithOneUniqueAndOneFk(db, null, null);
        assertThat(usersSnapshot(db)).isEqualTo(usersBefore);
    }

    @Test
    void c_oldHibernateSchemaWithRows_keepsRowsAndAddsNoDuplicateConstraints() throws Exception {
        String db = createDatabase("s_c");
        exec(db, USERS_DDL);
        exec(db, HIBERNATE_HABIT_DDL);
        List<String> usersBefore = usersSnapshot(db);

        boot(db);

        assertThat(history(db)).containsExactly("0|BASELINE|true", "1|SQL|true");
        assertThat(query(db, "SELECT count(*) FROM habits")).containsExactly("2");
        assertThat(query(db, "SELECT count(*) FROM habit_completions")).containsExactly("3");
        assertHabitTablesWithOneUniqueAndOneFk(db, "uk1jmmm9bn1u8tqv76tvux1htde", "fkdt8vmlabmctme9yn5fbnp9ipf");
        assertThat(usersSnapshot(db)).isEqualTo(usersBefore);
    }

    @Test
    void d_restart_isANoOpInEveryStartingState() throws Exception {
        String empty = createDatabase("s_d_empty");
        String usersFirst = createDatabase("s_d_users");
        exec(usersFirst, USERS_DDL);
        String hibernate = createDatabase("s_d_hibernate");
        exec(hibernate, USERS_DDL);
        exec(hibernate, HIBERNATE_HABIT_DDL);

        for (String db : List.of(empty, usersFirst, hibernate)) {
            boot(db);
            List<String> historyAfterFirstBoot = fullHistory(db);
            List<String> constraintsAfterFirstBoot = completionConstraints(db);

            boot(db);

            assertThat(fullHistory(db)).as("history in %s", db).isEqualTo(historyAfterFirstBoot);
            assertThat(completionConstraints(db)).as("constraints in %s", db).isEqualTo(constraintsAfterFirstBoot);
        }
    }

    // --- helpers ---

    /**
     * Boots the real app (application.yml: Flyway + ddl-auto=validate) against one database, then closes it.
     *
     * <p>The overrides are passed as command-line args on purpose: {@code SpringApplicationBuilder.properties()}
     * only sets <em>default</em> properties, which application.yml beats, so the app would silently connect to
     * {@code localhost:5432/habitflow} (whatever Postgres is listening there) instead of the container.
     */
    private static void boot(String db) {
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(HabitServiceApplication.class)
                .web(WebApplicationType.NONE)
                .listeners(new TestcontainersOnlyGuard(jdbcUrl(db)))
                .run(
                        "--spring.datasource.url=" + jdbcUrl(db),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.kafka.listener.auto-startup=false",
                        "--spring.kafka.admin.auto-create=false")) {
            // started = Flyway migrated and Hibernate validation passed
        }
    }

    /**
     * Pre-boot guard: once the environment is resolved (all property sources, application.yml included)
     * and before any bean exists (no Hikari pool, no Flyway), abort unless spring.datasource.url is
     * exactly this scenario's Testcontainers database.
     */
    private static final class TestcontainersOnlyGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
        private final String expectedUrl;

        TestcontainersOnlyGuard(String expectedUrl) {
            this.expectedUrl = expectedUrl;
        }

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            String resolved = event.getEnvironment().getProperty("spring.datasource.url");
            if (!expectedUrl.equals(resolved)) {
                throw new IllegalStateException("FlywayBaselineIT guard: refusing to boot, spring.datasource.url resolved to ["
                        + resolved + "] but this test may only use the Testcontainers database [" + expectedUrl + "]");
            }
        }
    }

    private static void assertHabitTablesWithOneUniqueAndOneFk(String db, String uniqueName, String fkName) throws SQLException {
        assertThat(query(db, "SELECT to_regclass('habits') IS NOT NULL AND to_regclass('habit_completions') IS NOT NULL"))
                .containsExactly("t");
        List<String> unique = query(db, """
                SELECT conname FROM pg_constraint WHERE conrelid = 'habit_completions'::regclass AND contype = 'u'
                  AND pg_get_constraintdef(oid) = 'UNIQUE (habit_id, completion_date)'""");
        List<String> fk = query(db, """
                SELECT conname FROM pg_constraint WHERE conrelid = 'habit_completions'::regclass AND contype = 'f'
                  AND pg_get_constraintdef(oid) = 'FOREIGN KEY (habit_id) REFERENCES habits(id)'""");
        assertThat(unique).hasSize(1);
        assertThat(fk).hasSize(1);
        // and nothing else of either kind on the table
        assertThat(query(db, "SELECT count(*) FROM pg_constraint WHERE conrelid = 'habit_completions'::regclass AND contype IN ('u','f')"))
                .containsExactly("2");
        if (uniqueName != null) assertThat(unique).containsExactly(uniqueName);
        if (fkName != null) assertThat(fk).containsExactly(fkName);
    }

    private static List<String> history(String db) throws SQLException {
        return query(db, "SELECT version || '|' || type || '|' || success FROM " + HISTORY + " ORDER BY installed_rank");
    }

    private static List<String> fullHistory(String db) throws SQLException {
        return query(db, "SELECT installed_rank || '|' || coalesce(version, '') || '|' || type || '|' || coalesce(checksum::text, '')"
                + " || '|' || installed_on FROM " + HISTORY + " ORDER BY installed_rank");
    }

    private static List<String> completionConstraints(String db) throws SQLException {
        return query(db, "SELECT conname || '|' || pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conrelid = 'habit_completions'::regclass ORDER BY conname");
    }

    private static List<String> usersSnapshot(String db) throws SQLException {
        List<String> snapshot = new ArrayList<>(query(db, """
                SELECT column_name || '|' || data_type || '|' || is_nullable || '|' || coalesce(column_default, '')
                FROM information_schema.columns WHERE table_name = 'users' ORDER BY ordinal_position"""));
        snapshot.addAll(query(db, "SELECT conname || '|' || pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conrelid = 'users'::regclass ORDER BY conname"));
        snapshot.addAll(query(db, "SELECT u::text FROM users u ORDER BY id"));
        return snapshot;
    }

    private static String createDatabase(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private static void exec(String db, String sql) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<String> query(String db, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) rows.add(rs.getString(1));
        }
        return rows;
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
    }
}
