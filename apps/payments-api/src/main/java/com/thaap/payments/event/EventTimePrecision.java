package com.thaap.payments.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class EventTimePrecision {

    private EventTimePrecision() {
    }

    public static Instant toPostgreSqlMicroseconds(Instant value) {
        if (value == null) {
            throw new IllegalArgumentException("Event time is required");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
