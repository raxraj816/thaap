package com.thaap.payments.simulation;

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
public class RiskSimulationDispatcher {

    private final JobLauncher jobLauncher;
    private final Job job;
    private final Executor executor;
    private final boolean workerEnabled;
    private final RiskSimulationMetrics metrics;

    public RiskSimulationDispatcher(JobLauncher jobLauncher,
            @Qualifier("historicalRiskSimulationJob") Job job,
            @Qualifier("riskSimulationExecutor") Executor executor,
            @Value("${thaap.simulation.worker-enabled:true}") boolean workerEnabled,
            RiskSimulationMetrics metrics) {
        this.jobLauncher = jobLauncher;
        this.job = job;
        this.executor = executor;
        this.workerEnabled = workerEnabled;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void queued(RiskSimulationQueuedEvent event) {
        if (!workerEnabled) {
            metrics.recordDispatch("worker_disabled");
            return;
        }
        executor.execute(() -> launchNow(event.runId()));
    }

    public void launchNow(UUID runId) {
        try {
            JobParameters parameters = new JobParametersBuilder()
                    .addString("runId", runId.toString(), true)
                    .toJobParameters();
            jobLauncher.run(job, parameters);
            metrics.recordDispatch("launched");
        } catch (Exception exception) {
            metrics.recordDispatch("launch_failed");
            throw new IllegalStateException("Historical simulation launch failed", exception);
        }
    }
}
