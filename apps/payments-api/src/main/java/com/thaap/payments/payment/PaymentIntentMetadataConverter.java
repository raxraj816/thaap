package com.thaap.payments.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import java.util.TreeMap;

@Converter
public class PaymentIntentMetadataConverter implements AttributeConverter<Map<String, String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, String> metadata) {
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata == null ? Map.of() : new TreeMap<>(metadata));
        } catch (Exception exception) {
            throw new IllegalArgumentException("PaymentIntent metadata could not be serialized", exception);
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String value) {
        try {
            if (value == null || value.isBlank()) {
                return Map.of();
            }
            return Map.copyOf(OBJECT_MAPPER.readValue(value, METADATA_TYPE));
        } catch (Exception exception) {
            throw new IllegalArgumentException("PaymentIntent metadata could not be deserialized", exception);
        }
    }
}
