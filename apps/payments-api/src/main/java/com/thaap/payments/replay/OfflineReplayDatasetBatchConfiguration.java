package com.thaap.payments.replay;

import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkOutcome;
import com.thaap.payments.replay.OfflineReplayDatasetWorker.WorkerClaim;
import java.util.UUID;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class OfflineReplayDatasetBatchConfiguration {

    @Bean
    @StepScope
    Tasklet offlineReplayDatasetChunkTasklet(OfflineReplayDatasetWorker worker,
            @Value("#{jobParameters['datasetId']}") String datasetId,
            @Value("#{jobParameters['workerId']}") String workerId,
            @Value("#{jobParameters['leaseToken']}") String leaseToken,
            @Value("#{jobParameters['fencingEpoch']}") Long fencingEpoch) {
        return (contribution, chunkContext) -> {
            WorkerClaim claim = new WorkerClaim(UUID.fromString(datasetId), workerId,
                    UUID.fromString(leaseToken), fencingEpoch);
            WorkOutcome outcome = worker.processNextChunk(claim);
            return outcome == WorkOutcome.CONTINUE ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step offlineReplayDatasetMaterializationStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            Tasklet offlineReplayDatasetChunkTasklet) {
        return new StepBuilder("offlineReplayDatasetMaterializationStep", jobRepository)
                .tasklet(offlineReplayDatasetChunkTasklet, transactionManager)
                .build();
    }

    @Bean
    Job offlineReplayDatasetMaterializationJob(JobRepository jobRepository,
            Step offlineReplayDatasetMaterializationStep,
            OfflineReplayDatasetJobListener listener) {
        return new JobBuilder("offlineReplayDatasetMaterializationJob", jobRepository)
                .listener(listener)
                .start(offlineReplayDatasetMaterializationStep)
                .build();
    }
}
