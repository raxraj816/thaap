package com.thaap.payments.projection.rebuild;

import java.util.List;
import java.util.UUID;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

public class ProjectionRebuildItemWriter implements ItemWriter<ProjectionRebuildInput> {

    private final UUID rebuildId;
    private final UUID leaseToken;
    private final long fencingEpoch;
    private final ProjectionRebuildStore store;
    private final ProjectionRebuildApplier applier;
    private final ProjectionRebuildExecutionProbe probe;

    // Chunk ordinal within this step execution, not within the rebuild. A resumed rebuild
    // starts counting again from one, which is what a crash-and-resume test needs to
    // distinguish the retried chunk from the first attempt at it.
    private long chunkOrdinal;

    public ProjectionRebuildItemWriter(UUID rebuildId, UUID leaseToken, long fencingEpoch,
            ProjectionRebuildStore store, ProjectionRebuildApplier applier,
            ProjectionRebuildExecutionProbe probe) {
        this.rebuildId = rebuildId;
        this.leaseToken = leaseToken;
        this.fencingEpoch = fencingEpoch;
        this.store = store;
        this.applier = applier;
        this.probe = probe;
    }

    @Override
    public void write(Chunk<? extends ProjectionRebuildInput> chunk) {
        List<? extends ProjectionRebuildInput> inputs = chunk.getItems();
        if (inputs.isEmpty()) {
            return;
        }
        if (inputs.size() > 100) {
            throw new IllegalStateException("Projection rebuild chunk exceeded 100 inputs");
        }
        chunkOrdinal++;
        probe.beforeChunkLease(rebuildId, chunkOrdinal);
        ProjectionRebuildLease lease = store.requireCurrentLease(
                rebuildId, leaseToken, fencingEpoch);
        for (ProjectionRebuildInput input : inputs) {
            applier.apply(input, lease);
        }
        probe.afterChunkEvidenceBeforeCheckpoint(rebuildId, chunkOrdinal);
        store.commitChunk(lease, inputs.size(), inputs.getLast().checkpointKey());
        probe.afterChunkCheckpoint(rebuildId, chunkOrdinal);
    }
}
