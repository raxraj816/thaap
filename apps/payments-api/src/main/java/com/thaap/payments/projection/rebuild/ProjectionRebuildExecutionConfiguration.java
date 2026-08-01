package com.thaap.payments.projection.rebuild;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ProjectionRebuildExecutionConfiguration {

    @Bean("projectionRebuildExecutor")
    Executor projectionRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("projection-rebuild-");
        executor.initialize();
        return executor;
    }
}
