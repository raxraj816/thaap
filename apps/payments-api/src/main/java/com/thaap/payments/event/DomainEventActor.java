package com.thaap.payments.event;

import java.util.Objects;

public record DomainEventActor(DomainEventActorType type, String id) {

    public DomainEventActor {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Domain event actor id must contain 1 to 128 characters");
        }
    }
}
