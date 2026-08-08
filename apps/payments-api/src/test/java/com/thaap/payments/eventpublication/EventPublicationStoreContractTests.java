package com.thaap.payments.eventpublication;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class EventPublicationStoreContractTests {

    @Autowired private EventPublicationStore store;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void claimFinalizationIsFencedAndLeaseRecoveryIsAppendOnly() {
        Merchant merchant = merchant("publication-fence");
        UUID streamId = UUID.randomUUID();
        append(merchant.getId(), streamId, "fence-event");
        Instant claimTime = DatabaseInstant.now().plusSeconds(1);

        PublicationClaim first = claim(claimTime, "worker-one", 1).getFirst();
        PublicationClaim takeover = claim(claimTime.plusSeconds(31),
                "worker-two", 1).getFirst();

        PublicationFinalization stale = finalize(first,
                PublicationSendResult.acknowledged(0, 1L),
                claimTime.plusSeconds(32));
        PublicationFinalization applied = finalize(takeover,
                PublicationSendResult.acknowledged(1, 2L),
                claimTime.plusSeconds(32));

        assertThat(stale).isEqualTo(PublicationFinalization.STALE_FENCING_TOKEN);
        assertThat(applied).isEqualTo(PublicationFinalization.APPLIED);
        assertThat(jdbcTemplate.queryForList("""
                SELECT outcome, safe_error_code, attempt_number
                  FROM event_publication_attempts
                 ORDER BY attempt_number
                """))
                .extracting(row -> row.get("OUTCOME"), row -> row.get("SAFE_ERROR_CODE"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LEASE_RECOVERED", "LEASE_EXPIRED"),
                        org.assertj.core.groups.Tuple.tuple("ACKNOWLEDGED", null));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM event_publications
                """, String.class)).isEqualTo("PUBLISHED");
    }

    @Test
    void predecessorOrderingAndAllThreeBudgetsRemainBounded() {
        Merchant noisy = merchant("publication-noisy");
        Merchant unrelated = merchant("publication-unrelated");
        UUID orderedStream = UUID.randomUUID();
        append(noisy.getId(), orderedStream, "ordered-one");
        append(noisy.getId(), orderedStream, "ordered-two");
        for (int index = 0; index < 24; index++) {
            append(noisy.getId(), UUID.randomUUID(), "noisy-" + index);
            append(unrelated.getId(), UUID.randomUUID(), "unrelated-" + index);
        }

        List<PublicationClaim> claims = claim(DatabaseInstant.now().plusSeconds(1),
                "fair-worker", 100);
        Map<UUID, Long> byTenant = claims.stream().collect(Collectors.groupingBy(
                PublicationClaim::merchantId, Collectors.counting()));
        Map<String, Long> byStream = claims.stream().collect(Collectors.groupingBy(
                claim -> claim.merchantId() + ":" + claim.streamType() + ":"
                        + claim.streamId(), Collectors.counting()));

        assertThat(claims).hasSize(40);
        assertThat(byTenant.values()).allMatch(count -> count <= 20);
        assertThat(byTenant).containsKeys(noisy.getId(), unrelated.getId());
        assertThat(byStream.values()).allMatch(count -> count <= 10);
        assertThat(claims.stream()
                .filter(claim -> claim.streamId().equals(orderedStream))
                .map(PublicationClaim::streamVersion).toList())
                .containsExactly(1L);
    }

    @Test
    void tenScheduledRetriesAllowRetryTenThenBlockWithoutDeletingEvent() {
        Merchant merchant = merchant("publication-retry-ceiling");
        append(merchant.getId(), UUID.randomUUID(), "retry-event");
        Instant dueAt = DatabaseInstant.now().plusSeconds(1);
        List<PublicationClaim> attempts = new ArrayList<>();

        for (int attempt = 1; attempt <= 11; attempt++) {
            PublicationClaim claim = claim(dueAt, "retry-worker", 1).getFirst();
            attempts.add(claim);
            finalize(claim, PublicationSendResult.failed(
                            PublicationOutcome.RETRYABLE_FAILURE,
                            PublicationSafeErrorCode.BROKER_DISCONNECTED),
                    dueAt.plusMillis(100));
            TimestampHolder next = jdbcTemplate.queryForObject("""
                    SELECT lifecycle_status, retry_count, next_attempt_at
                      FROM event_publications
                    """, (resultSet, rowNumber) -> new TimestampHolder(
                            resultSet.getString(1), resultSet.getInt(2),
                            resultSet.getTimestamp(3).toInstant()));
            if (attempt < 11) {
                assertThat(next.status()).isEqualTo("RETRY_SCHEDULED");
                dueAt = next.nextAttemptAt().plusMillis(1);
            } else {
                assertThat(next.status()).isEqualTo("BLOCKED");
                assertThat(next.retryCount()).isEqualTo(10);
            }
        }

        assertThat(attempts).hasSize(11);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication_attempts", Integer.class))
                .isEqualTo(11);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_events", Integer.class)).isEqualTo(1);
    }

    private Merchant merchant(String name) {
        return merchantRepository.saveAndFlush(new Merchant(name,
                name + "-key-" + UUID.randomUUID()));
    }

    private void append(UUID merchantId, UUID streamId, String correlationId) {
        transaction.executeWithoutResult(status -> eventWriter.append(
                command(merchantId, streamId, correlationId)));
    }

    private DomainEventCommand command(UUID merchantId, UUID streamId,
            String correlationId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 5000);
        payload.put("currency", "USD");
        payload.put("captureMethod", "AUTOMATIC");
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, streamId,
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION,
                DatabaseInstant.now().minusSeconds(1), correlationId, null,
                correlationId + "-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM,
                        "publication-contract"), TrafficOrigin.MERCHANT_API, payload);
    }

    private List<PublicationClaim> claim(Instant now, String workerId, int limit) {
        List<PublicationClaim> claims = transaction.execute(status ->
                store.claimDue(now, workerId, limit));
        return claims == null ? List.of() : claims;
    }

    private PublicationFinalization finalize(PublicationClaim claim,
            PublicationSendResult result, Instant completedAt) {
        return transaction.execute(status ->
                store.finalizeClaim(claim, result, completedAt));
    }

    private record TimestampHolder(String status, int retryCount,
            Instant nextAttemptAt) {
    }
}
