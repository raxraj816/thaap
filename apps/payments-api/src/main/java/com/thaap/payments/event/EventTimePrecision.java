package com.thaap.payments.event;

import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;

/**
 * The event-stream spelling of {@link DatabaseInstant}. It delegates rather than repeating the
 * rule, so the precision the canonical codec enforces and the precision the rest of the
 * application mints cannot drift apart.
 */
public final class EventTimePrecision {

    private EventTimePrecision() {
    }

    public static Instant toPostgreSqlMicroseconds(Instant value) {
        return DatabaseInstant.of(value);
    }
}
