package com.thaap.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Clock;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfiguration {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public JsonMapperBuilderCustomizer strictHttpJsonRequests() {
        return builder -> builder
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    /**
     * The application clock ticks at the precision the database stores, not the precision the
     * host kernel happens to offer. On Linux {@code Clock.systemUTC()} resolves to nanoseconds,
     * which PostgreSQL rounds away on write, so an untruncated clock hands every caller a value
     * that stops matching its own row as soon as it is read back.
     */
    @Bean
    public Clock clock() {
        return DatabaseInstant.tick(Clock.systemUTC());
    }
}
