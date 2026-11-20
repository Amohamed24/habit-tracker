package com.habitflow.habits.outbox;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** last_error text (HF-13): {@link OutboxPublisher#truncate} and {@link OutboxPublisher#describe}. */
class OutboxErrorTextTest {

    @Test
    void truncate_surrogatePairStraddlingTheLimit_isCutBeforeIt() {
        String emoji = "😀"; // one code point, two chars: high surrogate at 999, low at 1000
        String s = "a".repeat(999) + emoji + "tail";
        assertThat(Character.isHighSurrogate(s.charAt(999))).isTrue();

        String cut = OutboxPublisher.truncate(s);

        assertThat(cut).hasSize(999).isEqualTo("a".repeat(999));
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).as("no lone high surrogate").isFalse();
    }

    @Test
    void describe_nestedCause_isRootClassAndMessageOnly() {
        Throwable t = new IllegalStateException("outer",
                new KafkaException("Send failed", new IOException("connection reset")));

        assertThat(OutboxPublisher.describe(t)).isEqualTo("java.io.IOException: connection reset");
    }
}
