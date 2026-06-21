package com.thaap.payments.fraud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class FraudDecisionReasonCodeListConverter implements AttributeConverter<List<FraudDecisionReasonCode>, String> {

    // JPA converters are not Spring-managed here, so this mapper intentionally keeps DB JSON stable and local.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<FraudDecisionReasonCode>> REASON_CODES_TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<FraudDecisionReasonCode> reasonCodes) {
        try {
            return OBJECT_MAPPER.writeValueAsString(reasonCodes == null ? List.of() : List.copyOf(reasonCodes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Fraud decision reason codes could not be serialized", exception);
        }
    }

    @Override
    public List<FraudDecisionReasonCode> convertToEntityAttribute(String value) {
        try {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return List.copyOf(OBJECT_MAPPER.readValue(value, REASON_CODES_TYPE));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Fraud decision reason codes could not be deserialized", exception);
        }
    }
}
