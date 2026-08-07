package com.thaap.payments.projection.rebuild;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Test seam for deterministic crash, stale-worker and activation-ordering interleavings.
 *
 * <p>Mirrors {@link com.thaap.payments.replay.OfflineReplayDatasetExecutionProbe}. The rebuild
 * lease and activation invariants are only observable at the instants either side of a chunk
 * checkpoint and immediately before the generation switch commits; without a seam there, a test
 * can only assert the outcome of an uninterrupted run, which is what left {@code C50-CON-001}
 * and {@code C50-CON-002} unproven.
 */
@Component
public class ProjectionRebuildExecutionProbe {

    /**
     * A chunk is about to take its lease. This is the only instant in a rebuild where the
     * worker intends to write and holds no lock on the rebuild row — every later point is
     * inside the chunk transaction, which opens by locking that row {@code FOR UPDATE}. A
     * lease takeover is therefore only reachable here, which makes it the one place a
     * stale-worker interleaving can be forced without deadlocking against the victim.
     */
    public void beforeChunkLease(UUID rebuildId, long chunkOrdinal) {
        // Production intentionally has no behavior.
    }

    /** Chunk evidence rows are written; the checkpoint has not advanced. */
    public void afterChunkEvidenceBeforeCheckpoint(UUID rebuildId, long chunkOrdinal) {
        // Production intentionally has no behavior.
    }

    /** The checkpoint has advanced and committed for this chunk. */
    public void afterChunkCheckpoint(UUID rebuildId, long chunkOrdinal) {
        // Production intentionally has no behavior.
    }

    /**
     * Reconciliation evidence is validated against one snapshot; the generation switch has not
     * yet been written, and the definition row lock is held.
     */
    public void beforeGenerationActivation(UUID rebuildId) {
        // Production intentionally has no behavior.
    }
}
