package com.thaap.payments.projection.rebuild;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProjectionRebuildScheduler {

    private final ProjectionRebuildStore store;
    private final ProjectionRebuildDispatcher dispatcher;
    private final boolean workerEnabled;

    public ProjectionRebuildScheduler(ProjectionRebuildStore store,
            ProjectionRebuildDispatcher dispatcher,
            @Value("${thaap.projection-rebuild.worker-enabled:true}") boolean workerEnabled) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.workerEnabled = workerEnabled;
    }

    @Scheduled(fixedDelayString = "${thaap.projection-rebuild.poll-delay:5s}")
    public void recover() {
        if (!workerEnabled) {
            return;
        }
        for (UUID rebuildId : store.recoverable(8)) {
            dispatcher.dispatch(rebuildId);
        }
    }
}
