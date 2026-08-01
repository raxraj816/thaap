package com.thaap.payments.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DomainEventSchema(DomainEventType eventType, int eventVersion,
        Map<String, DomainEventFieldType> requiredFields,
        Map<String, DomainEventFieldType> optionalFields) {

    public DomainEventSchema {
        if (eventType == null || eventVersion <= 0) {
            throw new IllegalArgumentException("Event schema identity is invalid");
        }
        requiredFields = immutableCopy(requiredFields);
        optionalFields = immutableCopy(optionalFields);
        for (String field : requiredFields.keySet()) {
            if (optionalFields.containsKey(field)) {
                throw new IllegalArgumentException("Event field cannot be both required and optional: " + field);
            }
        }
    }

    private static Map<String, DomainEventFieldType> immutableCopy(
            Map<String, DomainEventFieldType> source) {
        if (source == null) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
