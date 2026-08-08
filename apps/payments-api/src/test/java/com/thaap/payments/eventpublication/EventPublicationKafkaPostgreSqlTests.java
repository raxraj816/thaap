package com.thaap.payments.eventpublication;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "thaap.event-publication.enabled=true",
        "thaap.event-publication.poll-delay=24h"
})
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventPublicationKafkaPostgreSqlTests {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:3.9.1");

    @Autowired private EventPublicationPublisher publisher;
    @Autowired private KafkaDomainEventSender sender;
    @Autowired private EventPublicationStore store;
    @Autowired private EventPublicationHealthIndicator healthIndicator;
    @Autowired private KafkaTemplate<byte[], byte[]> kafkaTemplate;
    @Autowired private KafkaAdmin kafkaAdmin;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    @Order(1)
    void realBrokerAcknowledgesExactStoredBytesKeyHeadersAndFrozenTopology()
            throws Exception {
        DomainEventWriteResult written = append("real-kafka-ack", UUID.randomUUID());
        Map<String, Long> sourceTruthBefore = sourceTruthCounts();
        PublicationClaim claim = publisher.claimDue("real-kafka-worker", 1).getFirst();

        PublicationFinalization finalization = publisher.publish(claim);
        ConsumerRecord<byte[], byte[]> record = recordsFor(claim.domainEventId(), 1)
                .getFirst();

        assertThat(finalization).isEqualTo(PublicationFinalization.APPLIED);
        assertThat(record.topic()).isEqualTo(EventPublicationProperties.TOPIC);
        assertThat(record.key()).isEqualTo(claim.recordKey());
        assertThat(record.value()).isEqualTo(claim.eventRecordBytes());
        assertThat(header(record, DomainEventKafkaHeaders.EVENT_ID))
                .isEqualTo(claim.domainEventId().toString());
        assertThat(header(record, DomainEventKafkaHeaders.MERCHANT_ID))
                .isEqualTo(claim.merchantId().toString());
        assertThat(header(record, DomainEventKafkaHeaders.EVENT_RECORD_SHA256))
                .isEqualTo(claim.eventRecordSha256());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM event_publications WHERE id = ?
                """, String.class, written.publicationId())).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT outcome FROM event_publication_attempts
                 WHERE publication_id = ?
                """, String.class, written.publicationId())).isEqualTo("ACKNOWLEDGED");
        assertThat(sourceTruthCounts()).isEqualTo(sourceTruthBefore);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT tgenabled = 'O' FROM pg_trigger
                 WHERE tgname = 'trg_projection_definitions_require_reconciliation'
                   AND NOT tgisinternal
                """, Boolean.class)).isTrue();
        assertFrozenBrokerAndProducerConfiguration();
        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @Order(2)
    void acknowledgementBeforeDatabaseFinalizationRepublishesIdenticalBytesAfterLeaseRecovery()
            throws Exception {
        append("ack-before-finalize", UUID.randomUUID());
        Map<String, Long> sourceTruthBefore = sourceTruthCounts();
        Instant firstClaimAt = DatabaseInstant.now().plusSeconds(1);
        PublicationClaim first = claim(firstClaimAt, "crashing-worker");

        PublicationSendResult lostAcknowledgement = sender.send(first);
        assertThat(lostAcknowledgement.outcome())
                .isEqualTo(PublicationOutcome.ACKNOWLEDGED);

        PublicationClaim takeover = claim(firstClaimAt.plusSeconds(31),
                "recovery-worker");
        PublicationFinalization staleFinalization = finalize(first,
                lostAcknowledgement, firstClaimAt.plusSeconds(31));
        PublicationSendResult recoveredSend = sender.send(takeover);
        PublicationFinalization finalization = finalize(takeover, recoveredSend,
                firstClaimAt.plusSeconds(32));
        List<ConsumerRecord<byte[], byte[]>> duplicates = recordsFor(
                first.domainEventId(), 2);

        assertThat(staleFinalization)
                .isEqualTo(PublicationFinalization.STALE_FENCING_TOKEN);
        assertThat(finalization).isEqualTo(PublicationFinalization.APPLIED);
        assertThat(duplicates).hasSize(2);
        assertThat(duplicates).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(first.recordKey());
            assertThat(record.value()).isEqualTo(first.eventRecordBytes());
        });
        assertThat(jdbcTemplate.queryForList("""
                SELECT outcome FROM event_publication_attempts
                 WHERE publication_id = ? ORDER BY attempt_number
                """, String.class, first.publicationId()))
                .containsExactly("LEASE_RECOVERED", "ACKNOWLEDGED");
        assertThat(sourceTruthCounts()).isEqualTo(sourceTruthBefore);
    }

    @Test
    @Order(3)
    void competingPostgreSqlClaimSkipsTheLockedPublication() throws Exception {
        append("postgres-skip-locked", UUID.randomUUID());
        Instant claimAt = DatabaseInstant.now().plusSeconds(1);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        TransactionTemplate firstTransaction = new TransactionTemplate(
                transactionManager);
        TransactionTemplate competingTransaction = new TransactionTemplate(
                transactionManager);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<PublicationClaim> lockedClaim = executor.submit(() ->
                    firstTransaction.execute(status -> {
                        List<PublicationClaim> claims = store.claimDue(claimAt,
                                "lock-holder", 1);
                        if (claims == null || claims.size() != 1) {
                            throw new AssertionError(
                                    "Lock holder did not claim exactly one publication");
                        }
                        firstClaimed.countDown();
                        await(releaseFirstTransaction,
                                "Timed out waiting to release publication lock");
                        return claims.getFirst();
                    }));

            await(firstClaimed,
                    "Timed out waiting for the first PostgreSQL claim");
            long competingStartedAt = System.nanoTime();
            List<PublicationClaim> competingClaims = competingTransaction.execute(
                    status -> store.claimDue(claimAt, "lock-competitor", 1));
            long competingDurationMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - competingStartedAt);

            assertThat(competingClaims).isEmpty();
            assertThat(competingDurationMillis).isLessThan(2_000L);
            releaseFirstTransaction.countDown();
            assertThat(lockedClaim.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseFirstTransaction.countDown();
        }
    }

    @Test
    @Order(4)
    void brokerOutageProducesClosedFailureAndRecoveryPublishesRetainedEvent()
            throws Exception {
        append("broker-outage-recovery", UUID.randomUUID());
        Map<String, Long> sourceTruthBefore = sourceTruthCounts();
        Instant firstClaimAt = DatabaseInstant.now().plusSeconds(1);
        PublicationClaim first = claim(firstClaimAt, "outage-worker");
        PublicationSendResult outageResult;

        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            outageResult = sender.send(first);
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        assertThat(outageResult.outcome()).isIn(PublicationOutcome.UNKNOWN,
                PublicationOutcome.RETRYABLE_FAILURE);
        PublicationFinalization outageFinalization = finalize(first, outageResult,
                DatabaseInstant.now());
        Instant recoveryClaimAt;
        if (outageFinalization == PublicationFinalization.APPLIED) {
            recoveryClaimAt = jdbcTemplate.queryForObject("""
                    SELECT next_attempt_at FROM event_publications WHERE id = ?
                    """, java.sql.Timestamp.class, first.publicationId()).toInstant()
                    .plusMillis(1);
        } else {
            assertThat(outageFinalization)
                    .isEqualTo(PublicationFinalization.EXPIRED_LEASE);
            recoveryClaimAt = firstClaimAt.plusSeconds(31);
        }
        PublicationClaim recovery = claim(recoveryClaimAt, "recovered-broker-worker");
        PublicationSendResult recoveryResult = sender.send(recovery);
        PublicationFinalization recoveryFinalization = finalize(recovery,
                recoveryResult, recoveryClaimAt.plusSeconds(1));

        assertThat(recoveryResult.outcome())
                .isEqualTo(PublicationOutcome.ACKNOWLEDGED);
        assertThat(recoveryFinalization).isEqualTo(PublicationFinalization.APPLIED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM event_publications WHERE id = ?
                """, String.class, first.publicationId())).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_events WHERE id = ?",
                Integer.class, first.domainEventId())).isEqualTo(1);
        assertThat(sourceTruthCounts()).isEqualTo(sourceTruthBefore);
    }

    @Test
    @Order(5)
    void realBrokerRetentionAndPartitionDriftFailReadiness() throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC,
                EventPublicationProperties.TOPIC);
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            alterRetention(admin, resource, Duration.ofDays(1).toMillis());
            assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);

            alterRetention(admin, resource, Duration.ofDays(7).toMillis());
            assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);

            admin.createPartitions(Map.of(EventPublicationProperties.TOPIC,
                            NewPartitions.increaseTo(7)))
                    .all().get(5, TimeUnit.SECONDS);
            assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);
        }
    }

    private DomainEventWriteResult append(String correlationId, UUID streamId) {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                correlationId, correlationId + "-key-" + UUID.randomUUID()));
        DomainEventWriteResult result = transaction.execute(status ->
                eventWriter.append(command(merchant.getId(), streamId, correlationId)));
        if (result == null) {
            throw new IllegalStateException("Domain event append returned no result");
        }
        return result;
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
                        "kafka-postgres-proof"), TrafficOrigin.MERCHANT_API, payload);
    }

    private PublicationClaim claim(Instant now, String workerId) {
        List<PublicationClaim> claims = transaction.execute(status ->
                store.claimDue(now, workerId, 1));
        if (claims == null || claims.size() != 1) {
            throw new IllegalStateException("Expected one publication claim");
        }
        return claims.getFirst();
    }

    private PublicationFinalization finalize(PublicationClaim claim,
            PublicationSendResult result, Instant completedAt) {
        return transaction.execute(status -> store.finalizeClaim(
                claim, result, completedAt));
    }

    private List<ConsumerRecord<byte[], byte[]>> recordsFor(UUID eventId,
            int expectedCount) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG,
                "publication-proof-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);
        List<ConsumerRecord<byte[], byte[]>> matches = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(EventPublicationProperties.TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (matches.size() < expectedCount && System.nanoTime() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(
                        Duration.ofMillis(250));
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    if (eventId.toString().equals(
                            header(record, DomainEventKafkaHeaders.EVENT_ID))) {
                        matches.add(record);
                    }
                }
            }
        }
        assertThat(matches).hasSize(expectedCount);
        return matches;
    }

    private String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.US_ASCII);
    }

    private void await(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError(failureMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failureMessage, exception);
        }
    }

    private Map<String, Long> sourceTruthCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (String table : List.of("payment_intents", "authorizations", "refunds",
                "ledger_entries", "fraud_decisions", "fraud_labels")) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table, Long.class);
            counts.put(table, count == null ? 0L : count);
        }
        return Map.copyOf(counts);
    }

    private void assertFrozenBrokerAndProducerConfiguration() throws Exception {
        Map<String, Object> producerConfiguration = kafkaTemplate.getProducerFactory()
                .getConfigurationProperties();
        assertThat(String.valueOf(producerConfiguration.get(ProducerConfig.ACKS_CONFIG)))
                .isEqualTo("all");
        assertThat(String.valueOf(producerConfiguration.get(
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG))).isEqualTo("true");
        assertThat(String.valueOf(producerConfiguration.get(
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG))).isEqualTo("10000");
        assertThat(String.valueOf(producerConfiguration.get(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG))).isEqualTo("30000");

        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription description = admin.describeTopics(
                            List.of(EventPublicationProperties.TOPIC))
                    .allTopicNames().get(5, TimeUnit.SECONDS)
                    .get(EventPublicationProperties.TOPIC);
            assertThat(description.partitions()).hasSize(6);
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC,
                    EventPublicationProperties.TOPIC);
            Config config = admin.describeConfigs(List.of(resource)).all()
                    .get(5, TimeUnit.SECONDS).get(resource);
            assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value())
                    .isEqualTo(Long.toString(Duration.ofDays(7).toMillis()));
        }
    }

    private void alterRetention(Admin admin, ConfigResource resource,
            long retentionMillis) throws Exception {
        AlterConfigOp operation = new AlterConfigOp(new ConfigEntry(
                TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMillis)),
                AlterConfigOp.OpType.SET);
        Map<ConfigResource, Collection<AlterConfigOp>> operations = Map.of(
                resource, List.of(operation));
        admin.incrementalAlterConfigs(operations).all().get(5, TimeUnit.SECONDS);
    }
}
