package com.thaap.payments.projection.rebuild;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

public class ProjectionRebuildJobListener implements JobExecutionListener {

    private final ProjectionRebuildStore store;
    private final ProjectionRebuildMetrics metrics;

    public ProjectionRebuildJobListener(ProjectionRebuildStore store,
            ProjectionRebuildMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            metrics.afterCommit("job", "completed");
            recordDuration(jobExecution, "completed");
            return;
        }
        store.failIfCurrent(rebuildId(jobExecution), leaseToken(jobExecution),
                fencingEpoch(jobExecution), "REBUILD_EXECUTION_FAILED");
        metrics.afterCommit("job", "failed");
        recordDuration(jobExecution, "failed");
    }

    private void recordDuration(JobExecution jobExecution, String outcome) {
        LocalDateTime startTime = jobExecution.getStartTime();
        LocalDateTime endTime = jobExecution.getEndTime();
        if (startTime == null || endTime == null) {
            // A job that never started or never ended has no honest duration to report;
            // recording zero would understate a hung rebuild rather than omit it.
            return;
        }
        metrics.jobDuration(outcome, Duration.between(startTime, endTime));
    }

    private UUID rebuildId(JobExecution execution) {
        return UUID.fromString(execution.getJobParameters().getString("rebuildId"));
    }

    private UUID leaseToken(JobExecution execution) {
        return UUID.fromString(execution.getJobParameters().getString("leaseToken"));
    }

    private long fencingEpoch(JobExecution execution) {
        Long value = execution.getJobParameters().getLong("fencingEpoch");
        if (value == null) {
            throw new IllegalStateException("Projection rebuild fence parameter is missing");
        }
        return value;
    }
}
