package com.thaap.payments.event;

/**
 * Signals that this instance is configured not to emit domain events.
 *
 * <p>Extends {@link RuntimeException} rather than {@link IllegalStateException} so it survives
 * Spring's persistence exception translation intact wherever it is thrown, for the same reason
 * {@code ProjectionRebuildFenceException} does.
 */
public final class DomainEventProducerDisabledException extends RuntimeException {

    public DomainEventProducerDisabledException(DomainEventType eventType) {
        super("Domain event producer is disabled; refusing to emit " + eventType.name());
    }
}
