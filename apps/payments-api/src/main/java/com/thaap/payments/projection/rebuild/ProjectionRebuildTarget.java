package com.thaap.payments.projection.rebuild;

import java.util.UUID;

public record ProjectionRebuildTarget(
        UUID rebuildId,
        UUID projectionDefinitionId,
        UUID projectionGenerationId,
        UUID cutoffManifestId,
        String projectionName,
        String handlerVersion,
        String lifecycleStatus,
        long fencingEpoch,
        long processedInputCount,
        long expectedInputCount,
        int currentReconciliationAttempt,
        String reconciliationSourceSha256,
        int catchupCycleCount,
        long catchupInputCount,
        long catchupRemainingCount,
        String latestCatchupSourceSha256) {

    public ProjectionRebuildLease asApplicationBoundary() {
        return new ProjectionRebuildLease(rebuildId, projectionDefinitionId,
                projectionGenerationId, cutoffManifestId, projectionName, handlerVersion,
                "operator-catch-up", new UUID(0, 0), fencingEpoch,
                processedInputCount, expectedInputCount, null,
                java.time.Instant.MAX,
                // An operator catch-up target is synthesised, never claimed or recovered.
                false);
    }
}
