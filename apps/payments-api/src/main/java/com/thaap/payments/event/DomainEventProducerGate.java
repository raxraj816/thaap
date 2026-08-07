package com.thaap.payments.event;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether this instance may emit domain events, and at which catalogue version.
 *
 * <p>A rolling deployment runs two application versions against one database. Without a gate, the
 * newer instance emits whatever its code happens to construct and the older consumers receive a
 * version they cannot decode — a failure that surfaces downstream, long after the event is
 * durable and unfixable. This gate makes the emitted version a deployment decision rather than a
 * consequence of which jar happens to be running.
 *
 * <p>It is fail-closed in both directions. A configured version the catalogue cannot serve for
 * every event type stops the instance from starting at all, rather than letting it run and
 * discover the gap one event type at a time. A disabled producer refuses emission outright
 * instead of silently dropping events, because a payment that believes it emitted an event and
 * did not is worse than a payment that fails loudly.
 */
@Component
public class DomainEventProducerGate {

    private final DomainEventCatalogue catalogue;
    private final boolean producerEnabled;
    private final int producerVersion;

    public DomainEventProducerGate(DomainEventCatalogue catalogue,
            @Value("${thaap.domain-events.producer-enabled:true}") boolean producerEnabled,
            @Value("${thaap.domain-events.producer-version:"
                    + DomainEventCatalogue.INITIAL_EVENT_VERSION + "}") int producerVersion) {
        this.catalogue = catalogue;
        this.producerEnabled = producerEnabled;
        this.producerVersion = producerVersion;
    }

    /**
     * Refuses to start when the configured producer version is not serveable for every event
     * type in the catalogue. Readiness that only reported the mismatch would leave an instance
     * accepting traffic it cannot honour.
     */
    @PostConstruct
    void requireCatalogueCanServeConfiguredVersion() {
        for (DomainEventType eventType : DomainEventType.values()) {
            try {
                catalogue.require(eventType, producerVersion);
            } catch (RuntimeException unsupported) {
                throw new IllegalStateException(
                        "Domain event producer version " + producerVersion
                                + " is not supported by the catalogue for " + eventType.name(),
                        unsupported);
            }
        }
    }

    public boolean producerEnabled() {
        return producerEnabled;
    }

    public int producerVersion() {
        return producerVersion;
    }

    /**
     * Called on the write path before anything becomes durable.
     *
     * @throws DomainEventProducerDisabledException when this instance must not emit
     * @throws DomainEventProducerVersionException when the command's version is not the version
     *     this deployment is configured to emit
     */
    public void requireEmissionAllowed(DomainEventType eventType, int eventVersion) {
        if (!producerEnabled) {
            throw new DomainEventProducerDisabledException(eventType);
        }
        if (eventVersion != producerVersion) {
            throw new DomainEventProducerVersionException(eventType, eventVersion,
                    producerVersion);
        }
    }
}
