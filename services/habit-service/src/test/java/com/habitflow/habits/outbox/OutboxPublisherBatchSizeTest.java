package com.habitflow.habits.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** outbox.batch-size validation and use (R1-06b / HF-12). */
class OutboxPublisherBatchSizeTest {

    @SuppressWarnings("unchecked")
    private static OutboxPublisher publisher(OutboxStore store, int batchSize) {
        return new OutboxPublisher(store, mock(KafkaTemplate.class), mock(OutboxBackoff.class), 20000, batchSize);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void batchSizeBelowOne_isRejected(int batchSize) {
        assertThatThrownBy(() -> publisher(mock(OutboxStore.class), batchSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outbox.batch-size must be >= 1");
    }

    @Test
    void batchSizeOne_isAccepted_andPassedToClaim() {
        OutboxStore store = mock(OutboxStore.class);
        when(store.claim(1)).thenReturn(List.of());

        assertThatCode(() -> publisher(store, 1).publishBatch()).doesNotThrowAnyException();
        verify(store).claim(1);
    }
}
