package com.thaap.payments.projection.rebuild;

import java.util.UUID;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ProjectionRebuildBatchConfiguration {

    public static final int CHUNK_SIZE = 100;

    @Bean
    @StepScope
    ProjectionRebuildItemReader projectionRebuildItemReader(JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['rebuildId']}") String rebuildId) {
        return new ProjectionRebuildItemReader(jdbcTemplate, UUID.fromString(rebuildId));
    }

    @Bean
    @StepScope
    ProjectionRebuildItemWriter projectionRebuildItemWriter(
            ProjectionRebuildStore store, ProjectionRebuildApplier applier,
            @Value("#{jobParameters['rebuildId']}") String rebuildId,
            @Value("#{jobParameters['leaseToken']}") String leaseToken,
            @Value("#{jobParameters['fencingEpoch']}") Long fencingEpoch) {
        return new ProjectionRebuildItemWriter(UUID.fromString(rebuildId),
                UUID.fromString(leaseToken), requiredFence(fencingEpoch), store, applier);
    }

    @Bean
    Step projectionShadowBuildStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ProjectionRebuildItemReader projectionRebuildItemReader,
            ProjectionRebuildItemWriter projectionRebuildItemWriter) {
        return new ChunkOrientedStepBuilder<ProjectionRebuildInput, ProjectionRebuildInput>(
                "projectionShadowBuildStep", jobRepository, CHUNK_SIZE)
                .reader(projectionRebuildItemReader)
                .writer(projectionRebuildItemWriter)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    @StepScope
    Tasklet projectionShadowBuildFinalizer(ProjectionRebuildStore store,
            @Value("#{jobParameters['rebuildId']}") String rebuildId,
            @Value("#{jobParameters['leaseToken']}") String leaseToken,
            @Value("#{jobParameters['fencingEpoch']}") Long fencingEpoch) {
        return (contribution, chunkContext) -> {
            store.finishBuild(UUID.fromString(rebuildId), UUID.fromString(leaseToken),
                    requiredFence(fencingEpoch));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step projectionShadowBuildFinalizationStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            Tasklet projectionShadowBuildFinalizer) {
        return new StepBuilder("projectionShadowBuildFinalizationStep", jobRepository)
                .tasklet(projectionShadowBuildFinalizer, transactionManager)
                .build();
    }

    @Bean
    Job projectionShadowBuildJob(JobRepository jobRepository,
            Step projectionShadowBuildStep, Step projectionShadowBuildFinalizationStep,
            ProjectionRebuildStore store, ProjectionRebuildMetrics metrics) {
        return new JobBuilder("projectionShadowBuildJob", jobRepository)
                .listener(new ProjectionRebuildJobListener(store, metrics))
                .start(projectionShadowBuildStep)
                .next(projectionShadowBuildFinalizationStep)
                .build();
    }

    private static long requiredFence(Long fencingEpoch) {
        if (fencingEpoch == null || fencingEpoch <= 0) {
            throw new IllegalStateException("Projection rebuild fence parameter is invalid");
        }
        return fencingEpoch;
    }
}
