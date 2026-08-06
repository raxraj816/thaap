package com.thaap.payments.bulk;

import com.thaap.payments.observability.DecisionIntelligenceMetrics;
import java.util.UUID;
import java.util.concurrent.Executor;
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
public class ReviewBulkDispatcher {

    private final JobLauncher jobLauncher;
    private final Job job;
    private final ReviewBulkActionStore store;
    private final Executor executor;
    private final boolean workerEnabled;
    private final DecisionIntelligenceMetrics metrics;
    private final String workerId = "review-bulk-" + UUID.randomUUID();

    public ReviewBulkDispatcher(JobLauncher jobLauncher,
            @Qualifier("reviewBulkAssignmentJob") Job job, ReviewBulkActionStore store,
            @Qualifier("reviewBulkExecutor") Executor executor,
            @Value("${thaap.review-bulk.worker-enabled:true}") boolean workerEnabled,
            DecisionIntelligenceMetrics metrics) {
        this.jobLauncher = jobLauncher;
        this.job = job;
        this.store = store;
        this.executor = executor;
        this.workerEnabled = workerEnabled;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void queued(ReviewBulkActionQueuedEvent event) {
        dispatch(event.requestId());
    }

    @Scheduled(fixedDelayString = "${thaap.review-bulk.recovery-poll-delay:5s}")
    public void recoverExpired() {
        if (!workerEnabled) return;
        for (UUID requestId : store.recoverableRequestIds(25)) {
            dispatch(requestId);
        }
    }

    public void dispatch(UUID requestId) {
        if (!workerEnabled) return;
        ReviewBulkActionStore.Lease lease = store.claim(requestId, workerId);
        if (lease != null) {
            metrics.bulkLease(lease.epoch() > 1 ? "RECOVERED" : "CLAIMED");
            executor.execute(() -> launch(lease));
        } else {
            metrics.bulkLease("NOT_CLAIMABLE");
        }
    }

    public void launchSynchronously(UUID requestId) {
        ReviewBulkActionStore.Lease lease = store.claim(requestId, workerId);
        if (lease == null) throw new IllegalStateException("Bulk review action is not claimable");
        metrics.bulkLease(lease.epoch() > 1 ? "RECOVERED" : "CLAIMED");
        launch(lease);
    }

    private void launch(ReviewBulkActionStore.Lease lease) {
        try {
            JobParameters parameters = new JobParametersBuilder()
                    .addString("requestId", lease.requestId().toString(), true)
                    .addString("merchantId", lease.merchantId().toString(), true)
                    .addLong("leaseEpoch", lease.epoch(), true)
                    .addString("leaseToken", lease.leaseToken().toString(), false)
                    .toJobParameters();
            jobLauncher.run(job, parameters);
            metrics.bulkLease("TERMINAL");
        } catch (Exception exception) {
            metrics.bulkLease("LAUNCH_FAILED");
            // The request remains RUNNING under a bounded lease. The recovery poller
            // will fence this worker and restart from durable item truth.
        }
    }
}
