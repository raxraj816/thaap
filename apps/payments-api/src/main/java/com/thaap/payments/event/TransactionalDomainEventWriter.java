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

    public TransactionalDomainEventWriter(EventStreamHeadRepository streamHeadRepository,
            EventStreamHeadInitializer streamHeadInitializer,
            DomainEventRepository domainEventRepository,
            EventPublicationRepository publicationRepository,
            CanonicalEventCodec codec, Clock clock) {
        this.streamHeadRepository = streamHeadRepository;
        this.streamHeadInitializer = streamHeadInitializer;
        this.domainEventRepository = domainEventRepository;
        this.publicationRepository = publicationRepository;
        this.codec = codec;
        this.clock = clock;
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
        EncodedDomainEvent encoded = codec.encode(command, eventId, streamVersion, recordedAt);
        DomainEvent event = domainEventRepository.saveAndFlush(
                new DomainEvent(eventId, command, streamVersion, encoded));
        EventPublication publication = publicationRepository.saveAndFlush(
                new EventPublication(command.merchantId(), eventId, encoded.recordedAt()));
        return new DomainEventWriteResult(event.getId(), publication.getId(), command.merchantId(),
                command.streamType(), command.streamId(), streamVersion, command.eventType(),
                command.eventVersion(), encoded.occurredAt(), encoded.recordedAt(),
                encoded.payloadSha256(), encoded.eventRecordSha256());
    }
}
