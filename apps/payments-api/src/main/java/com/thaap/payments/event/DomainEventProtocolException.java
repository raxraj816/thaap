package com.thaap.payments.event;

public final class DomainEventProtocolException extends RuntimeException {

    private final DomainEventProtocolViolation violation;

    public DomainEventProtocolException(DomainEventProtocolViolation violation) {
        super("Canonical domain event validation failed: " + violation.name());
        this.violation = violation;
    }

    public DomainEventProtocolException(DomainEventProtocolViolation violation, Throwable cause) {
        super("Canonical domain event validation failed: " + violation.name(), cause);
        this.violation = violation;
    }

    public DomainEventProtocolViolation violation() {
        return violation;
    }
}
