package com.thaap.payments.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThat(objectMapper.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT)).isFalse();
    }

    @Test
    void springHttpJsonMapperRejectsUnknownRequestFields() {
        tools.jackson.databind.json.JsonMapper.Builder builder =
                tools.jackson.databind.json.JsonMapper.builder();
        new JacksonConfiguration().strictHttpJsonRequests().customize(builder);
        tools.jackson.databind.json.JsonMapper mapper = builder.build();

        assertThat(mapper.isEnabled(
                tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThat(mapper.isEnabled(
                tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)).isFalse();
    }
}
