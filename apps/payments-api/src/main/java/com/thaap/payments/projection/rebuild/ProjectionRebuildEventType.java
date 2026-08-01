package com.thaap.payments.projection.rebuild;

public enum ProjectionRebuildEventType {
    QUEUED,
    LEASED,
    RESUMED,
    CHUNK_COMMITTED,
    CATCH_UP_STARTED,
    RECONCILED,
    ACTIVATED,
    CANCELED,
    FAILED,
    LEASE_RECOVERED,
    ROLLED_BACK
}
