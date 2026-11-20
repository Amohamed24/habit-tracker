package com.habitflow.habits.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Retry delay for a failed outbox row (R1-07 / HF-13): capped exponential backoff with equal jitter.
 *
 * <p>Only the delay is computed here. {@link OutboxStore#markFailed} applies it in SQL as
 * {@code clock_timestamp() + delay}, so next_attempt_at is database time, compared with database time by
 * the claim; JVM clock skew cannot shift when a row becomes due (the HF-11 bug). No {@code Clock} needed.
 */
@Component
public class OutboxBackoff {

    static final long BASE_MS = 1_000;
    static final long CAP_MS = 300_000; // 5 min
    // 2^9 s = 512 s is already above the cap, so larger exponents never need to be shifted (no overflow).
    static final int MAX_EXPONENT = 9;

    private final DoubleSupplier jitter;

    @Autowired
    public OutboxBackoff() {
        this(() -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param jitter values in [0, 1); injectable so tests are deterministic. */
    public OutboxBackoff(DoubleSupplier jitter) {
        this.jitter = jitter;
    }

    /**
     * Delay before the next attempt, given the row's attempts count after this failure (1 for the first).
     * Base = min(2^attempts s, 5 min); result = base/2 + jitter * base/2 ("equal jitter"), so it lies in
     * [base/2, base] and never exceeds the cap. Values below 1 are treated as 1.
     */
    public long delayMillis(int attempts) {
        int exponent = Math.min(Math.max(attempts, 1), MAX_EXPONENT);
        long base = Math.min(BASE_MS << exponent, CAP_MS);
        long half = base / 2;
        double j = Math.min(Math.max(jitter.getAsDouble(), 0.0), 1.0);
        return half + (long) (j * (base - half));
    }
}
