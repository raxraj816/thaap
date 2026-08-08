package com.thaap.payments.event;

/** Signals an attempt to emit a version this deployment is not configured to produce. */
public final class DomainEventProducerVersionException extends RuntimeException {

    public DomainEventProducerVersionException(DomainEventType eventType, int attemptedVersion,
            int producerVersion) {
        super("Domain event producer emits version " + producerVersion + "; refusing version "
                + attemptedVersion + " for " + eventType.name());
    }
}
