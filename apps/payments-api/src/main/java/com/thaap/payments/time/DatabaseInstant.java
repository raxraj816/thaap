package com.thaap.payments.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The precision every persisted instant in this application is allowed to carry.
 *
 * <p>PostgreSQL {@code timestamp(6)} stores microseconds and rounds anything finer away. The JVM
 * does not: {@link Instant#now()} resolves to microseconds on macOS but to nanoseconds on Linux.
 * An application that mints a nanosecond instant therefore holds a value its own database cannot
 * store, and the two disagree the moment the row is read back — the create response says
 * {@code .400031361Z} and every later read says {@code .400031Z}, for the same field of the same
 * record.
 *
 * <p>That divergence is invisible on a macOS development machine, because there the truncation has
 * already happened in the kernel and every comparison passes for free. It appears only on Linux,
 * which is to say only in CI and in production. Truncating at the source is what keeps the two
 * honest: an instant that never had sub-microsecond digits cannot lose them later.
 *
 * <p>Rounding is deliberately not used. PostgreSQL rounds, so a value could round up into a
 * microsecond the application never observed; truncation keeps the stored instant one the
 * application actually held.
 */
public final class DatabaseInstant {

    /** One microsecond, the finest interval PostgreSQL {@code timestamp(6)} can represent. */
    private static final Duration DATABASE_RESOLUTION = Duration.ofNanos(1000);

    private DatabaseInstant() {
    }

    /** The current instant at database precision. Replaces {@link Instant#now()}. */
    public static Instant now() {
        return of(Instant.now());
    }

    /**
     * Truncates an instant to database precision.
     *
     * @throws IllegalArgumentException when the value is absent, because a persisted instant that
     *     silently became null is a defect rather than a value to normalize
     */
    public static Instant of(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("Event time is required");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Wraps a clock so every reading it hands out is already at database precision.
     *
     * <p>Applied to the injected application clock, this fixes every caller at once rather than
     * asking each one to remember to truncate — the omission this class exists to prevent.
     */
    public static Clock tick(Clock clock) {
        return Clock.tick(clock, DATABASE_RESOLUTION);
    }
}
