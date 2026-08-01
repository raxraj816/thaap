package com.thaap.payments.event;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class DomainEventTypeConverter implements AttributeConverter<DomainEventType, String> {

    @Override
    public String convertToDatabaseColumn(DomainEventType eventType) {
        return eventType == null ? null : eventType.wireValue();
    }

    @Override
    public DomainEventType convertToEntityAttribute(String wireValue) {
        return wireValue == null ? null : DomainEventType.fromWireValue(wireValue);
    }
}
