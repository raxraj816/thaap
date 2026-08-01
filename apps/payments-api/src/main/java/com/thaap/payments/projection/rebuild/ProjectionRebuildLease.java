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
        Instant leaseExpiresAt) {
}
