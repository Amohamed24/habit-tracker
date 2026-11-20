package com.habitflow.habits.outbox;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Delay only (R1-07 / HF-13); next_attempt_at itself is DB time + this delay, see OutboxStore.markFailed. */
class OutboxBackoffTest {

    // Jitter at the top of its range gives the full base delay; at 0 it gives half.
    private static final double FULL = 1.0;
    private static final double NONE = 0.0;

    @Test
    void firstFailure_isOneToTwoSeconds() {
        assertThat(new OutboxBackoff(() -> FULL).delayMillis(1)).isEqualTo(2_000);
        assertThat(new OutboxBackoff(() -> NONE).delayMillis(1)).isEqualTo(1_000);
    }

    @Test
    void doublesPerAttempt_upTo256s() {
        OutboxBackoff backoff = new OutboxBackoff(() -> FULL);
        long expected = 2_000;
        for (int attempts = 1; attempts <= 8; attempts++) {
            assertThat(backoff.delayMillis(attempts)).as("attempts=%d", attempts).isEqualTo(expected);
            expected *= 2;
        }
        assertThat(backoff.delayMillis(8)).isEqualTo(256_000);
    }

    @Test
    void capsAtFiveMinutes_withoutOverflow() {
        OutboxBackoff full = new OutboxBackoff(() -> FULL);
        OutboxBackoff none = new OutboxBackoff(() -> NONE);
        for (int attempts : new int[] {9, 10, 31, 32, 63, 64, 1000, Integer.MAX_VALUE}) {
            assertThat(full.delayMillis(attempts)).as("attempts=%d", attempts).isEqualTo(300_000);
            assertThat(none.delayMillis(attempts)).as("attempts=%d", attempts).isEqualTo(150_000);
        }
        // Below 1 is treated as the first failure, never a zero or negative delay.
        assertThat(full.delayMillis(0)).isEqualTo(2_000);
        assertThat(full.delayMillis(-1)).isEqualTo(2_000);
        assertThat(full.delayMillis(Integer.MIN_VALUE)).isEqualTo(2_000);
    }

    @Test
    void jitter_staysBetweenHalfAndFullDelay() {
        SplittableRandom seeded = new SplittableRandom(13);
        OutboxBackoff backoff = new OutboxBackoff(seeded::nextDouble);
        for (int attempts = 1; attempts <= 12; attempts++) {
            long base = Math.min(1_000L << Math.min(attempts, 9), 300_000);
            for (int i = 0; i < 1_000; i++) {
                assertThat(backoff.delayMillis(attempts)).as("attempts=%d", attempts)
                        .isBetween(base / 2, base);
            }
        }
        // Out-of-range jitter values are clamped rather than escaping the bounds.
        assertThat(new OutboxBackoff(() -> 7.0).delayMillis(1)).isEqualTo(2_000);
        assertThat(new OutboxBackoff(() -> -3.0).delayMillis(1)).isEqualTo(1_000);
        assertThat(new OutboxBackoff(() -> Double.NaN).delayMillis(1)).isBetween(1_000L, 2_000L);
    }
}
