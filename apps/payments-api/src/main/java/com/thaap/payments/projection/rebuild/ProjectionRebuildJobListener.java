package com.thaap.payments.projection.rebuild;

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
            return;
        }
        store.failIfCurrent(rebuildId(jobExecution), leaseToken(jobExecution),
                fencingEpoch(jobExecution), "REBUILD_EXECUTION_FAILED");
        metrics.afterCommit("job", "failed");
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
