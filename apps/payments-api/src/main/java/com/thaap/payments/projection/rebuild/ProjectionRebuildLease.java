package com.thaap.payments.projection.rebuild;

import java.time.Instant;
import java.util.UUID;

public record ProjectionRebuildLease(
        UUID rebuildId,
        UUID projectionDefinitionId,
        UUID projectionGenerationId,
        UUID cutoffManifestId,
        String projectionName,
        String handlerVersion,
        String workerId,
        UUID leaseToken,
        long fencingEpoch,
        long processedInputCount,
        long expectedInputCount,
        String lastCheckpointKey,
        Instant leaseExpiresAt,
        // True when this lease was taken over from a worker whose lease had expired,
        // rather than claimed from a queued rebuild. Only the store can tell the two
        // apart, and an operator needs to: a recovered lease means a worker died
        // mid-rebuild. A higher fencing epoch does not imply recovery, so the fact is
        // carried rather than inferred.
        boolean recovered) {
}
