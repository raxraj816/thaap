package com.thaap.payments.webhook;

public interface WebhookDeliveryCountByEventType {

    String getEventType();

    long getDeliveryCount();
}
