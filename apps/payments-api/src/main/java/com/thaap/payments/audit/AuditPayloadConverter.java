package com.thaap.payments.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Converter
public class AuditPayloadConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(new TreeMap<>(payload));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Audit payload could not be serialized", exception);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return deepImmutableMap(OBJECT_MAPPER.readValue(value, PAYLOAD_TYPE));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Audit payload could not be deserialized", exception);
        }
    }

    private Map<String, Object> deepImmutableMap(Map<String, Object> payload) {
        Map<String, Object> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            immutable.put(entry.getKey(), deepImmutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private Object deepImmutableValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> immutable = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                immutable.put(String.valueOf(entry.getKey()), deepImmutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(immutable);
        }
        if (value instanceof Collection<?> collectionValue) {
            List<Object> immutable = new ArrayList<>();
            for (Object item : collectionValue) {
                immutable.add(deepImmutableValue(item));
            }
            return Collections.unmodifiableList(immutable);
        }
        return value;
    }
}
