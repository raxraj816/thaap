package com.thaap.payments.replay;

import com.thaap.payments.replay.OfflineReplayDatasetWorker.ReplayDatasetFailureCode;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.ReplayDatasetPopulationLimitException;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.ReplayDatasetSourceDriftException;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkerClaim;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

@Component
public class OfflineReplayDatasetJobListener implements JobExecutionListener {

    private final OfflineReplayDatasetWorker worker;
    private final OfflineReplayDatasetMetrics metrics;

    public OfflineReplayDatasetJobListener(OfflineReplayDatasetWorker worker,
            OfflineReplayDatasetMetrics metrics) {
        this.worker = worker;
        this.metrics = metrics;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            metrics.recordJob("batch_completed");
            return;
        }
        WorkerClaim claim = claim(jobExecution);
        boolean sourceDrift = jobExecution.getAllFailureExceptions().stream()
                .anyMatch(this::containsSourceDrift);
        if (sourceDrift) {
            worker.fail(claim, ReplayDatasetFailureCode.SOURCE_DIGEST_DRIFT);
            return;
        }
        boolean populationLimit = jobExecution.getAllFailureExceptions().stream()
                .anyMatch(this::containsPopulationLimit);
        if (populationLimit) {
            worker.fail(claim, ReplayDatasetFailureCode.POPULATION_LIMIT_EXCEEDED);
            return;
        }
        // Unknown dependency/runtime failures retain immutable progress. The
        // bounded lease scanner will recover them with a higher fencing epoch.
        metrics.recordJob("recoverable_failure");
    }

    private boolean containsSourceDrift(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ReplayDatasetSourceDriftException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsPopulationLimit(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ReplayDatasetPopulationLimitException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private WorkerClaim claim(JobExecution execution) {
        return new WorkerClaim(
                UUID.fromString(execution.getJobParameters().getString("datasetId")),
                execution.getJobParameters().getString("workerId"),
                UUID.fromString(execution.getJobParameters().getString("leaseToken")),
                execution.getJobParameters().getLong("fencingEpoch"));
    }
}
