package com.thaap.payments.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JacksonConfigurationTests {

    @Test
    void objectMapperUsesExplicitJavaTimeModuleWithStableIsoInstants() throws Exception {
        ObjectMapper objectMapper = new JacksonConfiguration().objectMapper();

        String serializedInstant = objectMapper.writeValueAsString(Instant.parse("2026-06-30T12:34:56.123456Z"));

        assertThat(serializedInstant).isEqualTo("\"2026-06-30T12:34:56.123456Z\"");
        assertThat(objectMapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
    }
}
