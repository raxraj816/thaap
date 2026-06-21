package com.thaap.payments.fraud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    // JPA converters are not Spring-managed here, so this mapper intentionally keeps DB JSON stable and local.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : List.copyOf(values));
        } catch (Exception exception) {
            throw new IllegalArgumentException("String list could not be serialized", exception);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String value) {
        try {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return List.copyOf(OBJECT_MAPPER.readValue(value, STRING_LIST_TYPE));
        } catch (Exception exception) {
            throw new IllegalArgumentException("String list could not be deserialized", exception);
        }
    }
}
