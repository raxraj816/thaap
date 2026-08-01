package com.thaap.payments.eventpublication;

public final class DomainEventKafkaHeaders {

    public static final String EVENT_ID = "thaap-event-id";
    public static final String MERCHANT_ID = "thaap-merchant-id";
    public static final String EVENT_RECORD_SHA256 = "thaap-event-record-sha256";

    private DomainEventKafkaHeaders() {
    }
}
