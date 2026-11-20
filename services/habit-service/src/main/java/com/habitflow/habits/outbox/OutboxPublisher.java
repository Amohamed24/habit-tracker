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
 * <p>A failed or un-acked row is left exactly as it was (sent_at NULL) and is retried on the next poll.
 * Attempts, last_error and backoff are R1-07 (HF-13).
 */
@Component
@Slf4j
public class OutboxPublisher {

    static final int BATCH_SIZE = 50;

    static final String HEADER_EVENT_TYPE = "event_type";
    static final String HEADER_EVENT_VERSION = "event_version";
    static final String HEADER_EVENT_ID = "event_id";

    private final OutboxStore store;
    // Values are the stored JSON text (StringSerializer), so no __TypeId__ header is added.
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long sendTimeoutMs;

    public OutboxPublisher(OutboxStore store, KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${outbox.send-timeout-ms:20000}") long sendTimeoutMs) {
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /** Publishes one batch and returns the number of rows marked sent (0 for an empty outbox). */
    @Transactional(timeout = 30)
    public int publishBatch() {
        List<OutboxRow> rows = store.claim(BATCH_SIZE);
        if (rows.isEmpty()) {
            return 0;
        }

        Map<OutboxRow, CompletableFuture<SendResult<String, String>>> inFlight = new LinkedHashMap<>();
        for (OutboxRow row : rows) {
            try {
                inFlight.put(row, kafkaTemplate.send(toRecord(row)));
            } catch (RuntimeException e) {
                // Synchronous failure (e.g. metadata not available within max.block.ms): stop here so a dead
                // broker costs one max.block.ms per batch, not one per row. The rest stay unsent for the next poll.
                log.warn("Send failed event_type={} event_id={} aggregate_id={}; stopping batch, {} row(s) left unsent: {}",
                        row.eventType(), row.id(), row.aggregateId(), rows.size() - inFlight.size(), e.toString());
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
                log.warn("No ack within {} ms event_type={} event_id={} aggregate_id={}; left unsent",
                        sendTimeoutMs, row.eventType(), row.id(), row.aggregateId());
            } catch (ExecutionException e) {
                log.warn("Publish failed event_type={} event_id={} aggregate_id={}; left unsent: {}",
                        row.eventType(), row.id(), row.aggregateId(), String.valueOf(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted waiting for acks; marking the {} acked row(s) and leaving the rest unsent",
                        acked.size());
                break;
            }
        }

        store.markSent(acked);
        return acked.size();
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
