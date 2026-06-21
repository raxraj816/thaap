package com.thaap.payments.fraud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import java.util.TreeMap;

@Converter
public class FeatureMapConverter implements AttributeConverter<Map<String, Object>, String> {

    // JPA converters are not Spring-managed here, so this mapper intentionally keeps DB JSON stable and local.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> FEATURE_MAP_TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> features) {
        try {
            return OBJECT_MAPPER.writeValueAsString(features == null ? Map.of() : new TreeMap<>(features));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Feature map could not be serialized", exception);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String value) {
        try {
            if (value == null || value.isBlank()) {
                return Map.of();
            }
            return Map.copyOf(OBJECT_MAPPER.readValue(value, FEATURE_MAP_TYPE));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Feature map could not be deserialized", exception);
        }
    }
}
