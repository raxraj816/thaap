package com.thaap.payments.projection;

/** Closed operational failure families safe for metrics and readiness diagnostics. */
public enum ProjectionConsumerFailure {
    SOURCE_UNAVAILABLE,
    DATABASE_UNAVAILABLE,
    INTERNAL_FAILURE
}
