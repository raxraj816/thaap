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

    public ProjectionRebuildItemWriter(UUID rebuildId, UUID leaseToken, long fencingEpoch,
            ProjectionRebuildStore store, ProjectionRebuildApplier applier) {
        this.rebuildId = rebuildId;
        this.leaseToken = leaseToken;
        this.fencingEpoch = fencingEpoch;
        this.store = store;
        this.applier = applier;
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
        ProjectionRebuildLease lease = store.requireCurrentLease(
                rebuildId, leaseToken, fencingEpoch);
        for (ProjectionRebuildInput input : inputs) {
            applier.apply(input, lease);
        }
        store.commitChunk(lease, inputs.size(), inputs.getLast().checkpointKey());
    }
}
