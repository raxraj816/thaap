package com.thaap.payments.projection.rebuild;

import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProjectionRebuildDispatcher {

    private final JobLauncher jobLauncher;
    private final Job job;
    private final ProjectionRebuildStore store;
    private final Executor executor;
    private final boolean workerEnabled;
    private final ProjectionRebuildMetrics metrics;
    private final String workerId = "projection-rebuild-" + UUID.randomUUID();

    public ProjectionRebuildDispatcher(JobLauncher jobLauncher,
            @Qualifier("projectionShadowBuildJob") Job job,
            ProjectionRebuildStore store,
            @Qualifier("projectionRebuildExecutor") Executor executor,
            @Value("${thaap.projection-rebuild.worker-enabled:true}") boolean workerEnabled,
            ProjectionRebuildMetrics metrics) {
        this.jobLauncher = jobLauncher;
        this.job = job;
        this.store = store;
        this.executor = executor;
        this.workerEnabled = workerEnabled;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void queued(ProjectionRebuildQueuedEvent event) {
        dispatch(event.rebuildId());
    }

    public void dispatch(UUID rebuildId) {
        if (!workerEnabled) {
            metrics.afterCommit("dispatch", "worker_disabled");
            return;
        }
        ProjectionRebuildLease lease = store.claim(rebuildId, workerId);
        if (lease == null) {
            return;
        }
        executor.execute(() -> launch(lease));
    }

    public void launchSynchronously(UUID rebuildId) {
        ProjectionRebuildLease lease = store.claim(rebuildId, workerId);
        if (lease == null) {
            throw new IllegalStateException("Projection rebuild is not claimable");
        }
        launch(lease);
    }

    private void launch(ProjectionRebuildLease lease) {
        try {
            JobParameters parameters = new JobParametersBuilder()
                    .addString("rebuildId", lease.rebuildId().toString(), true)
                    .addLong("fencingEpoch", lease.fencingEpoch(), true)
                    .addString("leaseToken", lease.leaseToken().toString(), false)
                    .toJobParameters();
            jobLauncher.run(job, parameters);
            metrics.afterCommit("dispatch", "launched");
        } catch (Exception exception) {
            store.failIfCurrent(lease.rebuildId(), lease.leaseToken(),
                    lease.fencingEpoch(), "REBUILD_LAUNCH_FAILED");
            metrics.afterCommit("dispatch", "failed");
        }
    }
}
