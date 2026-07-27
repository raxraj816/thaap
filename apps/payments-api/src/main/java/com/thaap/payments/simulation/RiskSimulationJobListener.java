package com.thaap.payments.simulation;

import java.util.UUID;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

public class RiskSimulationJobListener implements JobExecutionListener {

    private final RiskSimulationLifecycleService lifecycleService;
    private final RiskSimulationMetrics metrics;

    public RiskSimulationJobListener(RiskSimulationLifecycleService lifecycleService,
            RiskSimulationMetrics metrics) {
        this.lifecycleService = lifecycleService;
        this.metrics = metrics;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        UUID runId = runId(jobExecution);
        if (!lifecycleService.markRunning(runId, jobExecution.getId())) {
            throw new IllegalStateException("Simulation is not eligible to start");
        }
        metrics.recordJob("started", "accepted");
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        UUID runId = runId(jobExecution);
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            metrics.recordJob("completed", "succeeded");
            return;
        }
        lifecycleService.recordRecoverableFailure(runId, "BATCH_JOB", "RECOVERABLE_EXECUTION_FAILURE");
        metrics.recordJob("completed", "recoverable_failure");
    }

    private UUID runId(JobExecution jobExecution) {
        return UUID.fromString(jobExecution.getJobParameters().getString("runId"));
    }
}
