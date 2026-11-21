package com.habitflow.habits.outbox;

import com.habitflow.habits.config.KafkaConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes unsent outbox rows to {@code habit-events} (R1-06a / HF-11). One transaction per batch:
 * claim with FOR UPDATE SKIP LOCKED, send every row, wait for the broker acks against one deadline,
 * set sent_at on the acked rows only, commit.
 *
 * <p>The row locks are held across the send on purpose: if the process dies before commit the locks
 * are released and the rows are re-claimed, so already-acked rows are published again (D2, de-duplicated
 * downstream by event_id). How long they can be held is bounded by the producer settings in
 * application.yml (max.block.ms, delivery.timeout.ms) plus {@code outbox.send-timeout-ms}, all below the
 * 30 s transaction timeout.
 *
 * <p>Failure handling (R1-07 / HF-13): a row whose future failed, or that had no ack by the deadline, stays
 * unsent and gets attempts + 1, last_error and next_attempt_at = DB time + {@link OutboxBackoff} delay, in one
 * UPDATE in this same transaction. After a synchronous {@code send()} failure the rows not yet attempted are
 * backed off too ("not attempted: ..."), so a dead broker costs one max.block.ms per backoff step, not one
 * per poll; for those rows attempts counts failing batches, not sends. Acked rows are still marked sent.
 *
 * <p>last_error holds only the root cause's class name and message, truncated to
 * {@value #LAST_ERROR_MAX_CHARS} characters: never the payload, headers or producer config.
 */
@Component
@Slf4j
public class OutboxPublisher {

    static final String HEADER_EVENT_TYPE = "event_type";
    static final String HEADER_EVENT_VERSION = "event_version";
    static final String HEADER_EVENT_ID = "event_id";

    static final int LAST_ERROR_MAX_CHARS = 1000;

    private final OutboxStore store;
    // Values are the stored JSON text (StringSerializer), so no __TypeId__ header is added.
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxBackoff backoff;
    private final long sendTimeoutMs;
    private final int batchSize;

    public OutboxPublisher(OutboxStore store, KafkaTemplate<String, String> kafkaTemplate, OutboxBackoff backoff,
                           @Value("${outbox.send-timeout-ms:20000}") long sendTimeoutMs,
                           @Value("${outbox.batch-size:50}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("outbox.batch-size must be >= 1, was " + batchSize);
        }
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.backoff = backoff;
        this.sendTimeoutMs = sendTimeoutMs;
        this.batchSize = batchSize;
    }

    /** Publishes one batch and returns the number of rows marked sent (0 for an empty outbox). */
    @Transactional(timeout = 30)
    public int publishBatch() {
        List<OutboxRow> rows = store.claim(batchSize);
        if (rows.isEmpty()) {
            return 0;
        }

        Map<OutboxRow, CompletableFuture<SendResult<String, String>>> inFlight = new LinkedHashMap<>();
        List<OutboxStore.Failure> failed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            OutboxRow row = rows.get(i);
            try {
                inFlight.put(row, kafkaTemplate.send(toRecord(row)));
            } catch (RuntimeException e) {
                // Synchronous failure (e.g. metadata not available within max.block.ms): stop here so a dead
                // broker costs one max.block.ms per batch, not one per row. This row and the ones not yet
                // attempted all back off.
                String cause = describe(e);
                log.warn("Send failed event_type={} event_id={} aggregate_id={}; stopping batch, backing off {} row(s): {}",
                        row.eventType(), row.id(), row.aggregateId(), rows.size() - i, cause);
                failed.add(failure(row, cause));
                for (OutboxRow notAttempted : rows.subList(i + 1, rows.size())) {
                    failed.add(failure(notAttempted, "not attempted: " + cause));
                }
                break;
            }
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sendTimeoutMs);
        List<UUID> acked = new ArrayList<>(inFlight.size());
        for (Map.Entry<OutboxRow, CompletableFuture<SendResult<String, String>>> entry : inFlight.entrySet()) {
            OutboxRow row = entry.getKey();
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                RecordMetadata meta = entry.getValue().get(remaining, TimeUnit.NANOSECONDS).getRecordMetadata();
                acked.add(row.id());
                log.info("Published {} event_id={} aggregate_id={} partition={} offset={}",
                        row.eventType(), row.id(), row.aggregateId(), meta.partition(), meta.offset());
            } catch (TimeoutException e) {
                log.warn("No ack within {} ms event_type={} event_id={} aggregate_id={}; backing off",
                        sendTimeoutMs, row.eventType(), row.id(), row.aggregateId());
                failed.add(failure(row, TimeoutException.class.getName() + ": no ack within " + sendTimeoutMs + " ms"));
            } catch (ExecutionException e) {
                String cause = describe(e.getCause() == null ? e : e.getCause());
                log.warn("Publish failed event_type={} event_id={} aggregate_id={}; backing off: {}",
                        row.eventType(), row.id(), row.aggregateId(), cause);
                failed.add(failure(row, cause));
            } catch (InterruptedException e) {
                // Shutting down: the rows not awaited may well have been sent, so they are left untouched
                // (re-claimed later, de-duplicated downstream) rather than backed off.
                Thread.currentThread().interrupt();
                log.warn("Interrupted waiting for acks; marking the {} acked row(s) and leaving the rest unsent",
                        acked.size());
                break;
            }
        }

        store.markSent(acked);
        store.markFailed(failed);
        return acked.size();
    }

    private OutboxStore.Failure failure(OutboxRow row, String lastError) {
        return new OutboxStore.Failure(row.id(), truncate(lastError), backoff.delayMillis(row.attempts() + 1));
    }

    /** Root cause's fully qualified class name and message only (the name tells the two TimeoutExceptions apart). */
    static String describe(Throwable t) {
        Throwable root = t;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 32; depth++) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getName() : root.getClass().getName() + ": " + message;
    }

    /** At most {@value #LAST_ERROR_MAX_CHARS} chars, never ending on half a surrogate pair. */
    static String truncate(String s) {
        if (s.length() <= LAST_ERROR_MAX_CHARS) {
            return s;
        }
        int end = LAST_ERROR_MAX_CHARS;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static ProducerRecord<String, String> toRecord(OutboxRow row) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(KafkaConfig.HABIT_EVENTS_TOPIC, row.aggregateId(), row.payload());
        record.headers()
                .add(new RecordHeader(HEADER_EVENT_TYPE, utf8(row.eventType())))
                .add(new RecordHeader(HEADER_EVENT_VERSION, utf8(String.valueOf(row.eventVersion()))))
                .add(new RecordHeader(HEADER_EVENT_ID, utf8(row.id().toString())));
        return record;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
