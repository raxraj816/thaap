package com.thaap.payments.replay;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Test seam for deterministic crash and stale-worker interleavings. */
@Component
public class OfflineReplayDatasetExecutionProbe {

    public void afterChunkEvidenceBeforeCheckpoint(UUID datasetId, long chunkOrdinal) {
        // Production intentionally has no behavior.
    }

    public void beforeFinalization(UUID datasetId) {
        // Production intentionally has no behavior.
    }
}
