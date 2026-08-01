package com.thaap.payments.eventpublication;

import java.time.Instant;

public record PublicationHealthSnapshot(long pendingCount, long claimedCount,
        long retryingCount, long blockedCount, long publishedCount,
        Instant oldestUnpublishedAt, Instant lastAcknowledgedAt,
        String lastOutcome) {
}
