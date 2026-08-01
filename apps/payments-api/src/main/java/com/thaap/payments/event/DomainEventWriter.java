package com.thaap.payments.event;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends one committed fact to an already-owned domain transaction.
 * Callers must lock their authoritative aggregate before invoking this boundary.
 */
public interface DomainEventWriter {

    @Transactional(propagation = Propagation.MANDATORY)
    DomainEventWriteResult append(DomainEventCommand command);
}
