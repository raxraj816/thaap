package com.thaap.payments.eventpublication;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableConfigurationProperties(EventPublicationProperties.class)
public class EventPublicationConfiguration {

    @Bean
    @ConditionalOnProperty(name = "thaap.event-publication.enabled", havingValue = "true")
    NewTopic domainEventsTopic(EventPublicationProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        Long.toString(properties.retention().toMillis()))
                .config(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "69632")
                .build();
    }

    @Bean(name = "eventPublicationExecutor", destroyMethod = "close")
    @ConditionalOnProperty(name = "thaap.event-publication.enabled", havingValue = "true")
    ExecutorService eventPublicationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
