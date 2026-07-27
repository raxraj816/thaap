package com.thaap.payments.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.controls.AuthoredRuleCodec;
import com.thaap.payments.controls.AuthoredRuleEvaluator;
import com.thaap.payments.security.SensitiveValueHasher;
import java.time.Clock;
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
public class RiskSimulationBatchConfiguration {

    @Bean
    @StepScope
    HistoricalSimulationItemReader historicalSimulationItemReader(JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['runId']}") String runId,
            @Value("${thaap.simulation.chunk-size:100}") int chunkSize) {
        return new HistoricalSimulationItemReader(jdbcTemplate, UUID.fromString(runId), chunkSize);
    }

    @Bean
    @StepScope
    HistoricalPolicySimulationProcessor historicalPolicySimulationProcessor(
            JdbcTemplate jdbcTemplate, RiskSimulationCandidateSnapshotService snapshotService,
            AuthoredRuleCodec authoredRuleCodec, AuthoredRuleEvaluator authoredRuleEvaluator,
            SensitiveValueHasher sensitiveValueHasher, ObjectMapper objectMapper,
            @Value("#{jobParameters['runId']}") String runId) {
        return new HistoricalPolicySimulationProcessor(jdbcTemplate,
                UUID.fromString(runId), snapshotService, authoredRuleCodec, authoredRuleEvaluator,
                sensitiveValueHasher, objectMapper);
    }

    @Bean
    @StepScope
    HistoricalSimulationResultWriter historicalSimulationResultWriter(JdbcTemplate jdbcTemplate,
            Clock clock, RiskSimulationExecutionProbe executionProbe,
            @Value("#{jobParameters['runId']}") String runId) {
        return new HistoricalSimulationResultWriter(jdbcTemplate, UUID.fromString(runId), clock,
                executionProbe);
    }

    @Bean
    Step historicalRiskSimulationStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            HistoricalSimulationItemReader reader,
            HistoricalPolicySimulationProcessor processor,
            HistoricalSimulationResultWriter writer,
            @Value("${thaap.simulation.chunk-size:100}") int chunkSize) {
        return new ChunkOrientedStepBuilder<HistoricalSimulationCohortItem, HistoricalSimulationResult>(
                "historicalRiskSimulationStep", jobRepository, chunkSize)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    @StepScope
    Tasklet historicalRiskSimulationFinalizer(RiskSimulationReportService reportService,
            @Value("#{jobParameters['runId']}") String runId) {
        return (contribution, chunkContext) -> {
            reportService.finalizeSuccess(UUID.fromString(runId));
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step historicalRiskSimulationFinalizationStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            Tasklet historicalRiskSimulationFinalizer) {
        return new StepBuilder("historicalRiskSimulationFinalizationStep", jobRepository)
                .tasklet(historicalRiskSimulationFinalizer, transactionManager)
                .build();
    }

    @Bean
    Job historicalRiskSimulationJob(JobRepository jobRepository, Step historicalRiskSimulationStep,
            Step historicalRiskSimulationFinalizationStep,
            RiskSimulationLifecycleService lifecycleService,
            RiskSimulationMetrics metrics) {
        RiskSimulationJobListener listener = new RiskSimulationJobListener(lifecycleService, metrics);
        return new JobBuilder("historicalRiskSimulationJob", jobRepository)
                .listener(listener)
                .start(historicalRiskSimulationStep)
                .next(historicalRiskSimulationFinalizationStep)
                .build();
    }
}
