package com.thaap.payments.bulk;

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
public class ReviewBulkBatchConfiguration {

    @Bean
    @StepScope
    Tasklet reviewBulkAssignmentTasklet(ReviewBulkActionStore store,
            ReviewBulkItemExecutor itemExecutor,
            @Value("#{jobParameters['requestId']}") String requestId,
            @Value("#{jobParameters['merchantId']}") String merchantId,
            @Value("#{jobParameters['leaseToken']}") String leaseToken,
            @Value("#{jobParameters['leaseEpoch']}") Long leaseEpoch) {
        return (contribution, chunkContext) -> {
            ReviewBulkActionStore.Lease lease = new ReviewBulkActionStore.Lease(
                    UUID.fromString(requestId), UUID.fromString(merchantId),
                    UUID.fromString(leaseToken), requiredEpoch(leaseEpoch), "spring-batch");
            ReviewBulkActionStore.ClaimedItem item;
            while ((item = store.claimNext(lease)) != null) {
                itemExecutor.execute(item);
            }
            store.finalizeRequest(lease);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step reviewBulkAssignmentStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            Tasklet reviewBulkAssignmentTasklet) {
        return new StepBuilder("reviewBulkAssignmentStep", jobRepository)
                .tasklet(reviewBulkAssignmentTasklet, transactionManager).build();
    }

    @Bean
    Job reviewBulkAssignmentJob(JobRepository jobRepository,
            Step reviewBulkAssignmentStep) {
        return new JobBuilder("reviewBulkAssignmentJob", jobRepository)
                .start(reviewBulkAssignmentStep).build();
    }

    private static long requiredEpoch(Long value) {
        if (value == null || value <= 0) {
            throw new IllegalStateException("Bulk review lease epoch is invalid");
        }
        return value;
    }
}

