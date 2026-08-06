package com.thaap.payments.event;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalDomainEventWriter implements DomainEventWriter {

    private final EventStreamHeadRepository streamHeadRepository;
    private final EventStreamHeadInitializer streamHeadInitializer;
    private final DomainEventRepository domainEventRepository;
    private final EventPublicationRepository publicationRepository;
    private final CanonicalEventCodec codec;
    private final Clock clock;
    private final DomainEventWriterMetrics metrics;

    public TransactionalDomainEventWriter(EventStreamHeadRepository streamHeadRepository,
            EventStreamHeadInitializer streamHeadInitializer,
            DomainEventRepository domainEventRepository,
            EventPublicationRepository publicationRepository,
            CanonicalEventCodec codec, Clock clock,
            DomainEventWriterMetrics metrics) {
        this.streamHeadRepository = streamHeadRepository;
        this.streamHeadInitializer = streamHeadInitializer;
        this.domainEventRepository = domainEventRepository;
        this.publicationRepository = publicationRepository;
        this.codec = codec;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DomainEventWriteResult append(DomainEventCommand command) {
        Instant recordedAt = EventTimePrecision.toPostgreSqlMicroseconds(clock.instant());
        streamHeadInitializer.createIfAbsent(command.merchantId(), command.streamType(),
                command.streamId(), recordedAt);
        EventStreamHead head = streamHeadRepository.findForUpdate(
                        command.merchantId(), command.streamType(), command.streamId())
                .orElseThrow(() -> new IllegalStateException("Event stream head was not created"));
        long streamVersion = head.allocateNextVersion(recordedAt);
        UUID eventId = UUID.randomUUID();
        EncodedDomainEvent encoded;
        try {
            encoded = codec.encode(command, eventId, streamVersion, recordedAt);
        } catch (RuntimeException rejection) {
            // A codec or protocol rejection is the integrity failure C44-OBS-001 names.
            // It is counted before the exception propagates, because the transaction it
            // is about to roll back cannot carry an after-commit signal.
            metrics.rejected(command.eventType(), command.eventVersion());
            throw rejection;
        }
        DomainEvent event = domainEventRepository.saveAndFlush(
                new DomainEvent(eventId, command, streamVersion, encoded));
        EventPublication publication = publicationRepository.saveAndFlush(
                new EventPublication(command.merchantId(), eventId, encoded.recordedAt()));
        metrics.appendedAfterCommit(command.eventType(), command.eventVersion());
        return new DomainEventWriteResult(event.getId(), publication.getId(), command.merchantId(),
                command.streamType(), command.streamId(), streamVersion, command.eventType(),
                command.eventVersion(), encoded.occurredAt(), encoded.recordedAt(),
                encoded.payloadSha256(), encoded.eventRecordSha256());
    }
}
