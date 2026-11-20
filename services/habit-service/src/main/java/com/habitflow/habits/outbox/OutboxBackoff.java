package com.habitflow.habits.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    static final long CAP_MS = 300_000; // 5 min, the default for outbox.backoff.max-ms
    // 1000 << 53 still fits in a long; any larger cap is effectively unbounded, so the shift stops there.
    static final int SHIFT_LIMIT = 53;

    private final long capMs;
    // Smallest exponent whose 2^e s reaches capMs (9 for the default cap): larger attempts never shift further.
    private final int maxExponent;
    private final DoubleSupplier jitter;

    /**
     * @param capMs {@code outbox.backoff.max-ms}, default 5 min. Only ITs set it (HF-23 shortens it to 2 s so
     *              rows backed off during a broker outage are retried promptly once it's back).
     */
    @Autowired
    public OutboxBackoff(@Value("${outbox.backoff.max-ms:" + CAP_MS + "}") long capMs) {
        this(capMs, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param jitter values in [0, 1); injectable so tests are deterministic. Uses the default 5 min cap. */
    public OutboxBackoff(DoubleSupplier jitter) {
        this(CAP_MS, jitter);
    }

    public OutboxBackoff(long capMs, DoubleSupplier jitter) {
        if (capMs < 1) {
            throw new IllegalArgumentException("outbox.backoff.max-ms must be >= 1, was " + capMs);
        }
        this.capMs = capMs;
        int e = 0;
        while (e < SHIFT_LIMIT && (BASE_MS << e) < capMs) {
            e++;
        }
        this.maxExponent = Math.max(e, 1);
        this.jitter = jitter;
    }

    /**
     * Delay before the next attempt, given the row's attempts count after this failure (1 for the first).
     * Base = min(2^attempts s, cap), cap = outbox.backoff.max-ms (5 min by default); result = base/2 +
     * jitter * base/2 ("equal jitter"), so it lies in [base/2, base] and never exceeds the cap. Values below
     * 1 are treated as 1.
     */
    public long delayMillis(int attempts) {
        int exponent = Math.min(Math.max(attempts, 1), maxExponent);
        long base = Math.min(BASE_MS << exponent, capMs);
        long half = base / 2;
        double j = Math.min(Math.max(jitter.getAsDouble(), 0.0), 1.0);
        return half + (long) (j * (base - half));
    }
}
