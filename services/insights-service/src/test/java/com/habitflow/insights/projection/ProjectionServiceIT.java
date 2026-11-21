package com.habitflow.insights.projection;

import com.habitflow.insights.IntegrationTestBase;
import com.habitflow.insights.event.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETED;
import static com.habitflow.insights.event.EventEnvelope.HABIT_COMPLETION_UNDONE;
import static com.habitflow.insights.event.EventEnvelope.HABIT_CREATED;
import static com.habitflow.insights.projection.ProjectionTestSupport.DAY;
import static com.habitflow.insights.projection.ProjectionTestSupport.count;
import static com.habitflow.insights.projection.ProjectionTestSupport.countRowsForUser;
import static com.habitflow.insights.projection.ProjectionTestSupport.event;
import static com.habitflow.insights.projection.ProjectionTestSupport.freshUserId;
import static com.habitflow.insights.projection.ProjectionTestSupport.processedRows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProjectionService against the Testcontainers Postgres (R1-10 / HF-17). No spy here, so this class shares
 * the base's cached context; the forced-failure cases are in {@link ProjectionAtomicityIT}.
 */
class ProjectionServiceIT extends IntegrationTestBase {

    @Autowired ProjectionService projection;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        ProjectionTestSupport.truncate(jdbc);
    }

    /** AC: "Completed → 1; the same event_id again → 1, returns DUPLICATE." */
    @Test
    void completed_thenSameEventAgain_countsOnce_returnsDuplicate() {
        long user = freshUserId();
        EventEnvelope completed = event(HABIT_COMPLETED, user, DAY);

        assertThat(projection.apply(completed)).isEqualTo(ApplyResult.APPLIED);
        assertThat(count(jdbc, user, DAY)).isEqualTo(1);

        assertThat(projection.apply(completed)).isEqualTo(ApplyResult.DUPLICATE);
        assertThat(count(jdbc, user, DAY)).isEqualTo(1);
        assertThat(processedRows(jdbc, ProjectionService.CONSUMER, completed.eventId())).isEqualTo(1);
    }

    /** AC: "Completed then undone → 0; undone then completed → 0." (first half) */
    @Test
    void completedThenUndone_isZero() {
        long user = freshUserId();

        assertThat(projection.apply(event(HABIT_COMPLETED, user, DAY))).isEqualTo(ApplyResult.APPLIED);
        assertThat(count(jdbc, user, DAY)).isEqualTo(1);
        assertThat(projection.apply(event(HABIT_COMPLETION_UNDONE, user, DAY))).isEqualTo(ApplyResult.APPLIED);
        assertThat(count(jdbc, user, DAY)).isEqualTo(0);
    }

    /** AC: "Completed then undone → 0; undone then completed → 0." (second half; no clamping, so -1 first) */
    @Test
    void undoneThenCompleted_isZero_afterTemporaryMinusOne() {
        long user = freshUserId();

        assertThat(projection.apply(event(HABIT_COMPLETION_UNDONE, user, DAY))).isEqualTo(ApplyResult.APPLIED);
        assertThat(count(jdbc, user, DAY)).isEqualTo(-1);
        assertThat(projection.apply(event(HABIT_COMPLETED, user, DAY))).isEqualTo(ApplyResult.APPLIED);
        assertThat(count(jdbc, user, DAY)).isEqualTo(0);
    }

    /** AC: "An unknown type is recorded with no count change." */
    @Test
    void unknownType_isRecordedWithNoCountChange() {
        long user = freshUserId();
        EventEnvelope created = event(HABIT_CREATED, user, DAY);

        assertThat(projection.apply(created)).isEqualTo(ApplyResult.APPLIED);

        assertThat(processedRows(jdbc, ProjectionService.CONSUMER, created.eventId())).isEqualTo(1);
        assertThat(countRowsForUser(jdbc, user)).isZero();
        // Recorded means deduped too.
        assertThat(projection.apply(created)).isEqualTo(ApplyResult.DUPLICATE);
    }

    /** AC: "A different consumer name with the same event_id is processed independently." */
    @Test
    void differentConsumer_sameEventId_processedIndependently() {
        long user = freshUserId();
        EventEnvelope completed = event(HABIT_COMPLETED, user, DAY);

        assertThat(projection.apply(completed)).isEqualTo(ApplyResult.APPLIED);
        assertThat(projection.apply("other-consumer", completed)).isEqualTo(ApplyResult.APPLIED);

        assertThat(processedRows(jdbc, completed.eventId())).isEqualTo(2);
        assertThat(processedRows(jdbc, "other-consumer", completed.eventId())).isEqualTo(1);
        // One shared projection table, so two consumers applying it count twice; each consumer still once.
        assertThat(count(jdbc, user, DAY)).isEqualTo(2);
        assertThat(projection.apply("other-consumer", completed)).isEqualTo(ApplyResult.DUPLICATE);
    }

    /** Input check (not a Jira AC): a counted event without payload.userId is rejected before any write. */
    @Test
    void completed_missingUserId_throwsAndWritesNothing() {
        UUID eventId = UUID.randomUUID();
        var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("completionDate", DAY.toString());
        EventEnvelope bad = new EventEnvelope(eventId, HABIT_COMPLETED, 1, Instant.now(), "9", payload);

        assertThatThrownBy(() -> projection.apply(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload.userId missing");
        assertThat(processedRows(jdbc, eventId)).isZero();
    }
}
