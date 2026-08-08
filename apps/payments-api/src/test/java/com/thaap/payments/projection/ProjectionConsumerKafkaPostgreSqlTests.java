package com.thaap.payments.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.analytics.RiskAnalyticsService;
import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.event.DomainEvent;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.eventpublication.DomainEventKafkaHeaders;
import com.thaap.payments.eventpublication.TenantQualifiedStreamKey;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
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
        "thaap.event-publication.enabled=false",
        "thaap.projection-consumer.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ProjectionConsumerKafkaPostgreSqlTests {

    private static final String TOPIC = PaymentRiskProjectionConsumerService.TOPIC_NAME;
    private static final String PLATFORM_BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:3.9.1");

    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentIntentRepository paymentIntentRepository;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private DomainEventRepository eventRepository;
    @Autowired private PaymentRiskProjectionConsumerService consumerService;
    @Autowired private PaymentRiskProjectionMetrics projectionMetrics;
    @Autowired private RiskAnalyticsService analyticsService;
    @Autowired private QuarantineRecoveryService recoveryService;
    @Autowired private PlatformOperatorService platformOperatorService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private long nextSyntheticOffset;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeAll
    static void createTopic() throws Exception {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(properties)) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 6, (short) 1)))
                        .all().get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException exception) {
                if (!(exception.getCause() instanceof TopicExistsException)) {
                    throw exception;
                }
            }
        }
    }

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        transaction = new TransactionTemplate(transactionManager);
        nextSyntheticOffset = 0;
    }

    @Test
    void realBrokerPoisonCommitsSafeEvidenceAndAckRedeliveryIsOneEffect()
            throws Exception {
        MerchantFixture owner = merchant("consumer-real-broker");
        PaymentFixture payment = payment(owner, "USD", 1_900);
        DomainEvent event = append(payment, DomainEventType.PAYMENT_INTENT_CREATED,
                payment.occurredAt(), createdPayload(payment));
        byte[] key = TenantQualifiedStreamKey.encode(event.getMerchantId(),
                event.getStreamType(), event.getStreamId());
        ProducerRecord<byte[], byte[]> poison = kafkaRecord(key,
                "{}".getBytes(StandardCharsets.US_ASCII), event);
        ProducerRecord<byte[], byte[]> valid = kafkaRecord(key,
                event.getEventRecordBytes(), event);
        RecordMetadata poisonMetadata = send(poison);
        RecordMetadata validMetadata = send(valid);
        assertThat(validMetadata.offset()).isEqualTo(poisonMetadata.offset() + 1);
        List<ConsumerRecord<byte[], byte[]>> records = readPartitionRange(
                poisonMetadata.offset(), 2);
        PaymentRiskProjectionKafkaListener listener = new PaymentRiskProjectionKafkaListener(
                consumerService, projectionMetrics);
        Acknowledgment poisonAcknowledgment = mock(Acknowledgment.class);

        listener.receive(records.getFirst(), poisonAcknowledgment);

        verify(poisonAcknowledgment).acknowledge();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_transport_quarantine", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT received_record_size_bytes FROM consumer_transport_quarantine
                """, Long.class)).isEqualTo(2L);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE consumer_transport_quarantine SET reason_code = 'INVALID_SCHEMA'
                """))
                .isInstanceOf(DataAccessException.class);

        Acknowledgment lostAcknowledgment = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("forced acknowledgement loss"))
                .when(lostAcknowledgment).acknowledge();
        assertThatThrownBy(() -> listener.receive(records.get(1), lostAcknowledgment))
                .isInstanceOf(IllegalStateException.class);
        Acknowledgment recoveredAcknowledgment = mock(Acknowledgment.class);
        listener.receive(records.get(1), recoveredAcknowledgment);

        verify(recoveredAcknowledgment).acknowledge();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_risk_projections", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT SUM(payment_count) FROM merchant_risk_aggregates
                WHERE interval_type = 'HOUR' AND traffic_origin_policy = 'ALL'
                """, Long.class)).isOne();
    }

    @Test
    void forcedPostgreSqlDefinitionLockSerializesConcurrentDuplicate() throws Exception {
        MerchantFixture owner = merchant("consumer-concurrent-duplicate");
        PaymentFixture payment = payment(owner, "USD", 2_900);
        DomainEvent event = append(payment, DomainEventType.PAYMENT_INTENT_CREATED,
                payment.occurredAt(), createdPayload(payment));
        TransportedDomainEvent delivery = transported(event, nextSyntheticOffset++);
        CountDownLatch definitionLocked = new CountDownLatch(1);
        CountDownLatch releaseDefinition = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> blocker = executor.submit(() -> new TransactionTemplate(
                    transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM projection_definitions
                                WHERE projection_name = 'payment-risk-v1'
                                FOR UPDATE
                                """, UUID.class);
                        definitionLocked.countDown();
                        await(releaseDefinition, "definition lock release");
                    }));
            await(definitionLocked, "definition lock acquisition");
            Future<ProjectionProcessingOutcome> first = executor.submit(
                    () -> consumerService.process(delivery));
            Future<ProjectionProcessingOutcome> second = executor.submit(
                    () -> consumerService.process(delivery));
            try {
                awaitDatabaseWait("projection_definitions", 2);
            } finally {
                releaseDefinition.countDown();
            }

            assertThat(List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ProjectionProcessingOutcome.APPLIED,
                            ProjectionProcessingOutcome.DUPLICATE);
            blocker.get(10, TimeUnit.SECONDS);
        } finally {
            releaseDefinition.countDown();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_risk_projections", Integer.class)).isOne();
    }

    @Test
    void forcedRecoveryOrderIsDefinitionThenSourceThenInboxAndDoesNotDeadlock()
            throws Exception {
        MerchantFixture owner = merchant("consumer-recovery-order");
        PaymentFixture delayed = payment(owner, "USD", 9_000);
        PaymentFixture unrelated = payment(owner, "GBP", 3_000);
        DomainEvent delayedCreated = append(delayed,
                DomainEventType.PAYMENT_INTENT_CREATED, delayed.occurredAt(),
                createdPayload(delayed));
        DomainEvent delayedCapture = append(delayed, DomainEventType.CAPTURE_CREATED,
                delayed.occurredAt().plusSeconds(1), moneyPayload(9_000, "USD"));
        DomainEvent unrelatedCreated = append(unrelated,
                DomainEventType.PAYMENT_INTENT_CREATED, unrelated.occurredAt(),
                createdPayload(unrelated));
        assertThat(consumerService.process(transported(delayedCapture,
                nextSyntheticOffset++))).isEqualTo(ProjectionProcessingOutcome.HELD);
        assertThat(consumerService.process(transported(delayedCreated,
                nextSyntheticOffset++))).isEqualTo(ProjectionProcessingOutcome.APPLIED);
        PlatformOperatorService.MutationResult admin = platformOperatorService.bootstrap(
                PLATFORM_BOOTSTRAP_TOKEN, "pg-recovery-admin", "PG recovery admin",
                "pg-recovery-admin-request");
        PlatformOperatorService.MutationResult operator = platformOperatorService.create(
                admin.credential(), "pg-recovery-data", "PG recovery data",
                PlatformOperatorRole.DATA_OPERATOR, "pg-recovery-data-request");
        UUID quarantineId = jdbcTemplate.queryForObject("""
                SELECT id FROM consumer_quarantine WHERE domain_event_id = ?
                """, UUID.class, delayedCapture.getId());
        CountDownLatch sourceLocked = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> blocker = executor.submit(() -> new TransactionTemplate(
                    transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM domain_events WHERE id = ? FOR UPDATE
                                """, UUID.class, delayedCapture.getId());
                        sourceLocked.countDown();
                        await(releaseSource, "source lock release");
                    }));
            await(sourceLocked, "source lock acquisition");
            Future<QuarantineRecoveryService.RecoveryResult> recovery = executor.submit(
                    () -> recoveryService.retry(operator.credential(),
                            "pg-forced-recovery", quarantineId,
                            "pg-forced-recovery-request"));
            awaitDatabaseWait("domain_events", 1, recovery);
            Future<ProjectionProcessingOutcome> unrelatedWork = executor.submit(
                    () -> consumerService.process(transported(unrelatedCreated,
                            nextSyntheticOffset++)));
            try {
                awaitDatabaseWait("projection_definitions", 1);
            } finally {
                releaseSource.countDown();
            }

            assertThat(recovery.get(10, TimeUnit.SECONDS).recoveredCount()).isOne();
            assertThat(unrelatedWork.get(10, TimeUnit.SECONDS))
                    .isEqualTo(ProjectionProcessingOutcome.APPLIED);
            blocker.get(10, TimeUnit.SECONDS);
        } finally {
            releaseSource.countDown();
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT captured_amount_minor FROM payment_risk_projections
                WHERE payment_intent_id = ?
                """, Long.class, delayed.intent().getId())).isEqualTo(9_000L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM consumer_quarantine WHERE id = ?
                """, String.class, quarantineId)).isEqualTo("RESOLVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_risk_projections WHERE merchant_id = ?
                """, Integer.class, owner.merchant().getId())).isEqualTo(2);
    }

    @Test
    void PostgreSqlRangeQueryUsesTenantGenerationIntervalIndex() {
        MerchantFixture owner = merchant("analytics-index-plan");
        PaymentFixture payment = payment(owner, "USD", 5_900);
        DomainEvent event = append(payment, DomainEventType.PAYMENT_INTENT_CREATED,
                payment.occurredAt(), createdPayload(payment));
        consumerService.process(transported(event, nextSyntheticOffset++));
        PaymentFixture secondPayment = payment(owner, "USD", 6_100);
        DomainEvent secondEvent = append(secondPayment,
                DomainEventType.PAYMENT_INTENT_CREATED,
                secondPayment.occurredAt(), createdPayload(secondPayment));
        consumerService.process(transported(secondEvent, nextSyntheticOffset++));
        UUID generationId = UUID.fromString("00000000-0000-0000-0000-000000000912");
        String plan = transaction.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            List<String> lines = jdbcTemplate.queryForList("""
                    EXPLAIN (COSTS OFF)
                    SELECT currency, SUM(payment_count)
                    FROM merchant_risk_aggregates
                    WHERE merchant_id = ? AND projection_generation_id = ?
                      AND interval_type = 'HOUR' AND traffic_origin_policy = 'ALL'
                      AND interval_start >= ? AND interval_start < ?
                    GROUP BY currency ORDER BY currency
                    """, String.class, owner.merchant().getId(), generationId,
                    Timestamp.from(payment.occurredAt().minus(1, ChronoUnit.HOURS)),
                    Timestamp.from(payment.occurredAt().plus(1, ChronoUnit.HOURS)));
            return String.join("\n", lines);
        });

        assertThat(plan).containsAnyOf("idx_merchant_risk_aggregates_range",
                "idx_merchant_risk_aggregates_timeseries");

        RiskOperatorContext context = new RiskOperatorContext(
                owner.merchant().getId(), UUID.randomUUID(), "analytics-pg-viewer",
                RiskOperatorRole.VIEWER, "analytics-pg");
        RiskAnalyticsService.MerchantSummary summary = analyticsService.merchantSummary(
                context, null, null, "ALL");
        assertThat(summary.currencies()).singleElement().satisfies(currency -> {
            assertThat(currency.currency()).isEqualTo("USD");
            assertThat(currency.paymentCount()).isEqualTo(2);
        });
        assertThat(summary.freshness().sourceCutoff()).isNotNull();
        assertThat(summary.freshness().lastAppliedEventTime()).isNotNull();
        assertThat(summary.freshness().lastProcessingTime()).isNotNull();
        RiskAnalyticsService.MerchantTimeSeries timeSeries =
                analyticsService.merchantTimeSeries(context,
                        null, null, "HOUR", "ALL", 100);
        assertThat(timeSeries.points()).singleElement().satisfies(point ->
                assertThat(point.paymentCount()).isEqualTo(2));

        RiskAnalyticsService.PaymentSearchPage firstPage = analyticsService.searchPayments(
                context, 1, null, null, null, null);
        RiskAnalyticsService.PaymentSearchPage secondPage = analyticsService.searchPayments(
                context, 1, firstPage.nextCursor(), null, null, null);
        assertThat(firstPage.data()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.data()).hasSize(1);
        assertThat(secondPage.data().getFirst().paymentIntentId())
                .isNotEqualTo(firstPage.data().getFirst().paymentIntentId());
    }

    private MerchantFixture merchant(String name) {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                name, name + "-key-" + UUID.randomUUID()));
        return new MerchantFixture(merchant);
    }

    private PaymentFixture payment(MerchantFixture owner, String currency, long amount) {
        Customer customer = customerRepository.saveAndFlush(new Customer(
                owner.merchant().getId(), "email-" + UUID.randomUUID(), null));
        PaymentIntent intent = paymentIntentRepository.saveAndFlush(new PaymentIntent(
                owner.merchant().getId(), customer.getId(), amount, currency,
                CaptureMethod.AUTOMATIC, null, java.util.Map.of(),
                TrafficOrigin.MERCHANT_API));
        return new PaymentFixture(owner, intent, amount, currency,
                DatabaseInstant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS));
    }

    private DomainEvent append(PaymentFixture payment, DomainEventType eventType,
            Instant occurredAt, ObjectNode payload) {
        DomainEventCommand command = new DomainEventCommand(
                payment.owner().merchant().getId(), DomainStreamType.PAYMENT,
                payment.intent().getId(), eventType,
                DomainEventCatalogue.INITIAL_EVENT_VERSION,
                occurredAt.truncatedTo(ChronoUnit.MICROS),
                "projection-pg-" + UUID.randomUUID(), null,
                "projection-pg-request-" + UUID.randomUUID(), null,
                new DomainEventActor(DomainEventActorType.SYSTEM,
                        "projection-consumer-pg"), TrafficOrigin.MERCHANT_API, payload);
        DomainEventWriteResult result = transaction.execute(status -> eventWriter.append(command));
        if (result == null) {
            throw new IllegalStateException("Domain event append returned no result");
        }
        return eventRepository.findById(result.eventId()).orElseThrow();
    }

    private ObjectNode createdPayload(PaymentFixture payment) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", payment.amount());
        payload.put("currency", payment.currency());
        payload.put("captureMethod", "AUTOMATIC");
        return payload;
    }

    private ObjectNode moneyPayload(long amount, String currency) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", amount);
        payload.put("currency", currency);
        return payload;
    }

    private TransportedDomainEvent transported(DomainEvent event, long offset) {
        return new TransportedDomainEvent(TOPIC, 0, offset,
                TenantQualifiedStreamKey.encode(event.getMerchantId(),
                        event.getStreamType(), event.getStreamId()),
                event.getEventRecordBytes(), event.getId(), event.getMerchantId(),
                event.getEventRecordSha256());
    }

    private ProducerRecord<byte[], byte[]> kafkaRecord(byte[] key, byte[] value,
            DomainEvent source) {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(TOPIC, 0, key, value);
        record.headers().add(DomainEventKafkaHeaders.EVENT_ID,
                source.getId().toString().getBytes(StandardCharsets.US_ASCII));
        record.headers().add(DomainEventKafkaHeaders.MERCHANT_ID,
                source.getMerchantId().toString().getBytes(StandardCharsets.US_ASCII));
        record.headers().add(DomainEventKafkaHeaders.EVENT_RECORD_SHA256,
                source.getEventRecordSha256().getBytes(StandardCharsets.US_ASCII));
        return record;
    }

    private RecordMetadata send(ProducerRecord<byte[], byte[]> record) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(properties)) {
            return producer.send(record).get(10, TimeUnit.SECONDS);
        }
    }

    private List<ConsumerRecord<byte[], byte[]>> readPartitionRange(long firstOffset,
            int expectedCount) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "projection-proof-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        List<ConsumerRecord<byte[], byte[]>> result = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, firstOffset);
            Instant deadline = DatabaseInstant.now().plusSeconds(10);
            while (result.size() < expectedCount && DatabaseInstant.now().isBefore(deadline)) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<byte[], byte[]> record : records.records(partition)) {
                    if (record.offset() >= firstOffset && result.size() < expectedCount) {
                        result.add(record);
                    }
                }
            }
        }
        assertThat(result).hasSize(expectedCount);
        return result;
    }

    private void awaitDatabaseWait(String queryFragment, int expectedCount) {
        awaitDatabaseWait(queryFragment, expectedCount, null);
    }

    private void awaitDatabaseWait(String queryFragment, int expectedCount,
            Future<?> expectedBlockedWork) {
        Instant deadline = DatabaseInstant.now().plusSeconds(10);
        while (DatabaseInstant.now().isBefore(deadline)) {
            if (expectedBlockedWork != null && expectedBlockedWork.isDone()) {
                try {
                    expectedBlockedWork.get();
                } catch (Exception exception) {
                    throw new AssertionError(
                            "Work completed exceptionally before reaching PostgreSQL lock",
                            exception);
                }
                throw new AssertionError(
                        "Work completed before reaching the expected PostgreSQL lock");
            }
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND query ILIKE ?
                    """, Integer.class, "%" + queryFragment + "%");
            if (count != null && count >= expectedCount) {
                return;
            }
            Thread.onSpinWait();
        }
        List<Map<String, Object>> activity = jdbcTemplate.queryForList("""
                SELECT state, wait_event_type, wait_event, LEFT(query, 200) AS query
                FROM pg_stat_activity
                WHERE datname = current_database() AND pid <> pg_backend_pid()
                ORDER BY pid
                """);
        throw new AssertionError("Timed out proving PostgreSQL wait on " + queryFragment
                + "; activity=" + activity);
    }

    private void await(CountDownLatch latch, String boundary) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + boundary);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + boundary, exception);
        }
    }

    private record MerchantFixture(Merchant merchant) {
    }

    private record PaymentFixture(MerchantFixture owner, PaymentIntent intent,
            long amount, String currency, Instant occurredAt) {
    }
}
