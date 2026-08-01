package com.thaap.payments.replay;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OfflineReplayDatasetExecutionConfiguration {

    @Bean("offlineReplayDatasetExecutor")
    Executor offlineReplayDatasetExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("offline-replay-dataset-");
        executor.initialize();
        return executor;
    }
}
