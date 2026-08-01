package com.thaap.payments.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class DomainEventWriterContractTests {

    @Autowired private DomainEventWriter eventWriter;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private EventPublicationRepository publicationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleaner databaseCleaner;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS event_atomicity_probes (
                    id UUID PRIMARY KEY,
                    marker VARCHAR(64) NOT NULL)
                """);
        jdbcTemplate.update("DELETE FROM event_atomicity_probes");
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void sourceProbeEventAndPublicationCommitInOneTransaction() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("event-writer-commit", "event-writer-commit-key"));
        UUID probeId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();

        DomainEventWriteResult result = transaction.execute(status -> {
            jdbcTemplate.update("INSERT INTO event_atomicity_probes (id, marker) VALUES (?, ?)",
                    probeId, "commit");
            return eventWriter.append(command(merchant.getId(), streamId));
        });

        assertThat(result).isNotNull();
        assertThat(result.streamVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_atomicity_probes", Integer.class)).isEqualTo(1);
        DomainEvent event = eventRepository.findById(result.eventId()).orElseThrow();
        EventPublication publication = publicationRepository.findById(
                result.publicationId()).orElseThrow();
        assertThat(event.getMerchantId()).isEqualTo(merchant.getId());
        assertThat(event.getStreamId()).isEqualTo(streamId);
        assertThat(event.getEventType()).isEqualTo(DomainEventType.PAYMENT_INTENT_CREATED);
        assertThat(publication.getDomainEventId()).isEqualTo(event.getId());
        assertThat(publication.getLifecycleStatus()).isEqualTo(EventPublicationStatus.PENDING);
    }

    @Test
    void rollbackAfterAppendRemovesSourceEventPublicationAndStreamAllocation() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("event-writer-rollback", "event-writer-rollback-key"));
        UUID streamId = UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO event_atomicity_probes (id, marker) VALUES (?, ?)",
                    UUID.randomUUID(), "rollback");
            eventWriter.append(command(merchant.getId(), streamId));
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_atomicity_probes", Integer.class)).isZero();
        assertThat(eventRepository.count()).isZero();
        assertThat(publicationRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_stream_heads", Integer.class)).isZero();
    }

    @Test
    void codecFailureRollsBackTheTestOwnedSourceMutation() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("event-writer-codec-failure", "event-writer-codec-key"));
        ObjectNode invalidPayload = validPayload();
        invalidPayload.put("cardNumber", "4242424242424242");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO event_atomicity_probes (id, marker) VALUES (?, ?)",
                    UUID.randomUUID(), "codec-failure");
            eventWriter.append(command(merchant.getId(), UUID.randomUUID(), invalidPayload));
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prohibited field");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_atomicity_probes", Integer.class)).isZero();
        assertThat(eventRepository.count()).isZero();
        assertThat(publicationRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_stream_heads", Integer.class)).isZero();
    }

    @Test
    void writerRejectsCallsWithoutAnOwningDomainTransaction() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("event-writer-mandatory", "event-writer-mandatory-key"));

        assertThatThrownBy(() -> eventWriter.append(
                command(merchant.getId(), UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("existing transaction");

        assertThat(eventRepository.count()).isZero();
        assertThat(publicationRepository.count()).isZero();
    }

    @Test
    void versionsAreGapFreePerTenantQualifiedStreamAndIndependentAcrossTenants() {
        Merchant firstMerchant = merchantRepository.saveAndFlush(
                new Merchant("event-writer-tenant-one", "event-writer-tenant-one-key"));
        Merchant secondMerchant = merchantRepository.saveAndFlush(
                new Merchant("event-writer-tenant-two", "event-writer-tenant-two-key"));
        UUID sharedStreamId = UUID.randomUUID();

        List<Long> firstVersions = transaction.execute(status -> List.of(
                eventWriter.append(command(firstMerchant.getId(), sharedStreamId)).streamVersion(),
                eventWriter.append(command(firstMerchant.getId(), sharedStreamId)).streamVersion()));
        Long secondVersion = transaction.execute(status -> eventWriter.append(
                command(secondMerchant.getId(), sharedStreamId)).streamVersion());

        assertThat(firstVersions).containsExactly(1L, 2L);
        assertThat(secondVersion).isEqualTo(1L);
        assertThat(eventRepository.countByMerchantId(firstMerchant.getId())).isEqualTo(2);
        assertThat(eventRepository.countByMerchantId(secondMerchant.getId())).isEqualTo(1);
    }

    private DomainEventCommand command(UUID merchantId, UUID streamId) {
        return command(merchantId, streamId, validPayload());
    }

    private DomainEventCommand command(UUID merchantId, UUID streamId,
            ObjectNode payload) {
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, streamId,
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION, Instant.now().minusSeconds(1),
                "event-writer-contract", null, "request-event-writer", null,
                new DomainEventActor(DomainEventActorType.SYSTEM, "test-owned-probe"),
                TrafficOrigin.MERCHANT_API, payload);
    }

    private ObjectNode validPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 1299);
        payload.put("currency", "USD");
        payload.put("captureMethod", "AUTOMATIC");
        return payload;
    }
}
