package com.thaap.payments.simulation;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RiskSimulationExecutionConfiguration {

    @Bean("riskSimulationExecutor")
    Executor riskSimulationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("risk-simulation-");
        executor.initialize();
        return executor;
    }
}
