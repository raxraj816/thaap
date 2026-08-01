package com.thaap.payments.replay;

import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkerClaim;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OfflineReplayDatasetDispatcher {

    private final JobLauncher jobLauncher;
    private final Job job;
    private final Executor executor;
    private final OfflineReplayDatasetWorker worker;
    private final OfflineReplayDatasetMetrics metrics;
    private final boolean workerEnabled;
    private final int recoveryBatchSize;

    public OfflineReplayDatasetDispatcher(JobLauncher jobLauncher,
            @Qualifier("offlineReplayDatasetMaterializationJob") Job job,
            @Qualifier("offlineReplayDatasetExecutor") Executor executor,
            OfflineReplayDatasetWorker worker, OfflineReplayDatasetMetrics metrics,
            @Value("${thaap.replay-datasets.worker-enabled:true}") boolean workerEnabled,
            @Value("${thaap.replay-datasets.recovery-batch-size:10}") int recoveryBatchSize) {
        this.jobLauncher = jobLauncher;
        this.job = job;
        this.executor = executor;
        this.worker = worker;
        this.metrics = metrics;
        this.workerEnabled = workerEnabled;
        this.recoveryBatchSize = recoveryBatchSize;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void queued(ReplayDatasetQueuedEvent event) {
        dispatch(event.datasetId());
    }

    @Scheduled(fixedDelayString = "${thaap.replay-datasets.recovery-poll-delay:5s}")
    public void recoverBacklog() {
        if (!workerEnabled) {
            return;
        }
        for (UUID datasetId : worker.recoverableDatasetIds(recoveryBatchSize)) {
            dispatch(datasetId);
        }
    }

    public void dispatch(UUID datasetId) {
        if (!workerEnabled) {
            metrics.recordDispatch("worker_disabled");
            return;
        }
        try {
            executor.execute(() -> claimAndLaunch(datasetId));
        } catch (RejectedExecutionException exception) {
            // The manifest is already durable because this path runs after
            // commit. A bounded recovery scan is the retry authority; never
            // turn executor saturation into an ambiguous create response.
            metrics.recordDispatch("queue_full");
        }
    }

    /** Synchronous operational/test entry point using the same fenced Batch job. */
    public void launchNow(UUID datasetId) {
        if (!workerEnabled) {
            metrics.recordDispatch("worker_disabled");
            return;
        }
        claimAndLaunch(datasetId);
    }

    private void claimAndLaunch(UUID datasetId) {
        String workerId = "offline-replay-" + UUID.randomUUID();
        WorkerClaim claim = worker.claim(datasetId, workerId);
        if (claim == null) {
            metrics.recordDispatch("not_claimable");
            return;
        }
        try {
            JobParameters parameters = new JobParametersBuilder()
                    .addString("datasetId", datasetId.toString(), true)
                    .addString("workerId", workerId, false)
                    .addString("leaseToken", claim.leaseToken().toString(), true)
                    .addLong("fencingEpoch", claim.fencingEpoch(), true)
                    .toJobParameters();
            jobLauncher.run(job, parameters);
            metrics.recordDispatch("launched");
        } catch (Exception exception) {
            metrics.recordDispatch("launch_failed");
        }
    }
}
