package com.habitflow.insights.projection;

import com.habitflow.insights.IntegrationTestBase;
import com.habitflow.insights.event.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETED;
import static com.habitflow.insights.projection.ProjectionTestSupport.DAY;
import static com.habitflow.insights.projection.ProjectionTestSupport.count;
import static com.habitflow.insights.projection.ProjectionTestSupport.event;
import static com.habitflow.insights.projection.ProjectionTestSupport.freshUserId;
import static com.habitflow.insights.projection.ProjectionTestSupport.processedRows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * The processed_events insert and the projection upsert commit or roll back together (R1-10 / HF-17).
 *
 * <p>{@code @SpyBean JdbcTemplate} (Boot 3.2) forces failures on the real code path; the spy gives this class
 * its own context, which is why these cases are not in {@link ProjectionServiceIT}. The spy is reset after
 * each test (Boot's default {@code MockReset.AFTER}). Each forced failure first checks, inside the
 * transaction, that the row it expects to be rolled back was really written.
 */
class ProjectionAtomicityIT extends IntegrationTestBase {

    private static final String PROCESSED_TABLE = "processed_events";
    private static final String COUNTS_TABLE = "daily_completion_counts";

    @Autowired ProjectionService projection;
    @SpyBean JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        ProjectionTestSupport.truncate(jdbc);
    }

    /**
     * AC: "A failure inside the upsert also rolls back the processed_events row; a retry counts once."
     * The forced exception comes after the processed_events insert, in place of the upsert.
     */
    @Test
    void upsertFails_rollsBackProcessedEvent_andRetryCountsOnce() {
        long user = freshUserId();
        EventEnvelope completed = event(HABIT_COMPLETED, user, DAY);
        AtomicInteger ledgerRowsSeenInTx = new AtomicInteger(-1);
        failOnUpdateOf(COUNTS_TABLE, false,
                () -> ledgerRowsSeenInTx.set(processedRows(jdbc, completed.eventId())));

        assertThatThrownBy(() -> projection.apply(completed)).hasMessageContaining("forced failure");

        assertThat(ledgerRowsSeenInTx.get()).as("processed_events row written before the failure").isEqualTo(1);
        assertThat(processedRows(jdbc, completed.eventId())).as("processed_events after rollback").isZero();
        assertThat(count(jdbc, user, DAY)).isNull();

        Mockito.reset(jdbc); // the redelivery: same envelope, no failure
        assertThat(projection.apply(completed)).isEqualTo(ApplyResult.APPLIED);
        assertThat(count(jdbc, user, DAY)).isEqualTo(1);
        assertThat(projection.apply(completed)).isEqualTo(ApplyResult.DUPLICATE);
        assertThat(count(jdbc, user, DAY)).isEqualTo(1);
    }

    /** Vice versa: the projection row is really written, then the transaction fails; neither row is left. */
    @Test
    void upsertWrittenThenFailure_rollsBackProjectionAndProcessedEvent() {
        long user = freshUserId();
        EventEnvelope completed = event(HABIT_COMPLETED, user, DAY);
        AtomicInteger countSeenInTx = new AtomicInteger(Integer.MIN_VALUE);
        failOnUpdateOf(COUNTS_TABLE, true, () -> countSeenInTx.set(count(jdbc, user, DAY)));

        assertThatThrownBy(() -> projection.apply(completed)).hasMessageContaining("forced failure");

        assertThat(countSeenInTx.get()).as("projection row written before the failure").isEqualTo(1);
        assertThat(count(jdbc, user, DAY)).as("projection after rollback").isNull();
        assertThat(processedRows(jdbc, completed.eventId())).as("processed_events after rollback").isZero();
    }

    /**
     * No partial write on early failure: the processed_events insert itself fails, so the upsert never runs.
     * Nothing was written before the throw, so this is not a rollback proof (see the two tests above for that).
     */
    @Test
    void processedEventInsertFails_noPartialWrite() {
        long user = freshUserId();
        EventEnvelope completed = event(HABIT_COMPLETED, user, DAY);
        failOnUpdateOf(PROCESSED_TABLE, false, () -> { });

        assertThatThrownBy(() -> projection.apply(completed)).hasMessageContaining("forced failure");

        assertThat(processedRows(jdbc, completed.eventId())).isZero();
        assertThat(count(jdbc, user, DAY)).isNull();
    }

    /**
     * Stubs {@code update(String, Object...)}: the first statement touching {@code table} runs {@code inTx}
     * (after the real statement if {@code runRealFirst}) and then throws; every other statement is real.
     */
    private void failOnUpdateOf(String table, boolean runRealFirst, Runnable inTx) {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (!sql.contains("INSERT INTO " + table)) {
                return inv.callRealMethod();
            }
            if (runRealFirst) {
                inv.callRealMethod();
            }
            inTx.run();
            throw new IllegalStateException("forced failure on " + table);
        }).when(jdbc).update(anyString(), any(Object[].class));
    }
}
