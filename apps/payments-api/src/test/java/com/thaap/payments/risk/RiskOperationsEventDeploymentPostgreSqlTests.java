package com.thaap.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventProducer;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.eventpublication.EventPublicationPublisher;
import com.thaap.payments.eventpublication.KafkaDomainEventSender;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C46-OPS-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}).
 *
 * <p>Two of the row's partitions have no mechanism to exercise, and that is recorded rather than
 * simulated. There is no producer-version configuration in the codebase, so "old/new application
 * order" and "producer off/on/off" have no switch to flip; what exists is the publication outbox,
 * whose disabled state is the default of the {@code test} profile and is asserted here as the
 * broker-absent partition. The mixed-version partition is covered as far as the catalogue allows:
 * every {@code RISK_PLATFORM} fact must resolve to a supported schema, and an unsupported version
 * must not.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.event-publication.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class RiskOperationsEventDeploymentPostgreSqlTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private ApplicationContext applicationContext;
    @Autowired private RiskOperationsEventProducer producer;
    @Autowired private DomainEventCatalogue catalogue;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
    }

    /**
     * The row's central claim: retained V64 operational facts are never backfilled as events. A
     * migration that synthesised history would hand every downstream consumer a stream of facts
     * nothing ever emitted, with no producer, actor or request behind them.
     */
    @Test
    void aRetainedV64UpgradeDoesNotBackfillOperationalFactsAsEvents() {
        String schema = "risk_retained_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try {
            String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
            DriverManagerDataSource retainedDataSource = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(retainedDataSource).schemas(schema)
                    .defaultSchema(schema).locations("classpath:db/migration")
                    .target("64").load().migrate();

            JdbcTemplate retained = new JdbcTemplate(retainedDataSource);
            UUID merchantId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            UUID paymentIntentId = UUID.randomUUID();
            retained.update("""
                    INSERT INTO %s.merchants (id, name, status, api_key_prefix, api_key_hash,
                        created_at)
                    VALUES (?, 'retained-risk-merchant', 'ACTIVE', 'retained_', ?,
                        CURRENT_TIMESTAMP)
                    """.formatted(schema), merchantId, "a".repeat(64));
            retained.update("""
                    INSERT INTO %s.customers (id, merchant_id, email_hash, created_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """.formatted(schema), customerId, merchantId, "b".repeat(64));
            retained.update("""
                    INSERT INTO %s.payment_intents (id, merchant_id, customer_id, amount,
                        currency, capture_method, status, metadata, created_at, updated_at)
                    VALUES (?, ?, ?, 4200, 'USD', 'AUTOMATIC', 'SUCCEEDED', '{}',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(schema), paymentIntentId, merchantId, customerId);
            long retainedReviewCases = retained.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".manual_review_cases", Long.class);

            Flyway.configure().dataSource(retainedDataSource).schemas(schema)
                    .defaultSchema(schema).locations("classpath:db/migration")
                    .load().migrate();

            assertThat(retained.queryForObject("SELECT status FROM " + schema
                    + ".payment_intents WHERE id = ?", String.class, paymentIntentId))
                    .isEqualTo("SUCCEEDED");
            assertThat(retained.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".manual_review_cases", Long.class)).isEqualTo(retainedReviewCases);
            assertThat(retained.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".domain_events", Integer.class)).isZero();
            assertThat(retained.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".event_publications", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    /**
     * Nothing scans operational tables to synthesise events. Source rows written directly must
     * stay eventless until a producer call actually happens, which is what makes the retained
     * upgrade above a property of the system rather than an accident of timing.
     */
    @Test
    void operationalSourceRowsProduceNoEventsUntilTheProducerIsCalled() {
        Fixture fixture = seedPaymentStream("no-backfill");
        assertThat(count("domain_events")).isZero();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                producer.fraudLabelRecorded(fixture.context(), fixture.paymentIntentId(),
                        DatabaseInstant.now().minusSeconds(1), "risk-deployment-request", "FRAUD",
                        "MANUAL_REVIEW", DatabaseInstant.now().minusSeconds(1),
                        "STOLEN_PAYMENT_CREDENTIAL", TrafficOrigin.MERCHANT_API));

        assertThat(count("domain_events")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT event_type FROM domain_events", String.class))
                .containsExactly("fraud_label.recorded");
    }

    /**
     * A broker outage must not roll back a risk-operations mutation. The publication row is
     * written in the same transaction as the event; Kafka is outside it.
     */
    @Test
    void withNoBrokerRiskOperationsStillCommitAndAccumulatePendingEvidence() {
        assertThat(applicationContext.getBeanNamesForType(EventPublicationPublisher.class))
                .describedAs("the publisher must be absent, or this is not a broker-absent test")
                .isEmpty();
        assertThat(applicationContext.getBeanNamesForType(KafkaDomainEventSender.class)).isEmpty();

        Fixture fixture = seedPaymentStream("broker-absent");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                producer.manualReviewResolved(fixture.context(), fixture.paymentIntentId(),
                        DatabaseInstant.now().minusSeconds(1), "risk-broker-absent-request",
                        "CONFIRMED_FRAUD", "STOLEN_PAYMENT_CREDENTIAL",
                        TrafficOrigin.MERCHANT_API));

        assertThat(count("domain_events")).isEqualTo(1);
        assertThat(count("event_publications")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT lifecycle_status FROM event_publications", String.class))
                .containsExactly("PENDING");

        // Pending work is durable against a configuration rollback: it cannot be deleted and
        // cannot be marked published without the publisher.
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM event_publications"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("event_publications")).isEqualTo(1);
    }

    /**
     * The risk-operations event surface claims no Kafka, inbox, projection, rebuild or
     * offline-dataset behavior. That is a structural claim, so it is asserted structurally: the producer's only collaborator is the
     * event writer. A dependency added here is how this surface quietly grows a second one.
     */
    @Test
    void theRiskProducerCollaboratesOnlyWithTheEventWriter() {
        Constructor<?>[] constructors = RiskOperationsEventProducer.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes())
                .containsExactly(DomainEventWriter.class);

        assertThat(Arrays.stream(RiskOperationsEventProducer.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(field -> field.getType().getName())
                .filter(name -> name.startsWith("com.thaap")))
                .containsExactly(DomainEventWriter.class.getName());
    }

    /**
     * Mixed-version operation must never emit a schema the catalogue does not support. There is
     * no producer-version switch to roll forward and back, so what is asserted is the invariant
     * that switch would have protected: every risk fact the producer can emit resolves at the
     * supported version, and nothing resolves above it.
     */
    @Test
    void everyRiskPlatformFactResolvesAtTheSupportedVersionAndNothingAboveIt() {
        List<DomainEventType> riskFacts = Arrays.stream(DomainEventType.values())
                .filter(type -> type.producer() == DomainEventProducer.RISK_PLATFORM)
                .toList();
        assertThat(riskFacts).isNotEmpty();

        for (DomainEventType type : riskFacts) {
            assertThat(catalogue.require(type, DomainEventCatalogue.INITIAL_EVENT_VERSION))
                    .describedAs("risk fact %s has no supported schema", type)
                    .isNotNull();
            assertThatThrownBy(() -> catalogue.require(type,
                    DomainEventCatalogue.INITIAL_EVENT_VERSION + 1))
                    .describedAs("risk fact %s resolved an unsupported version", type)
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(catalogue.schemas().keySet()).containsAll(riskFacts);
    }

    private Fixture seedPaymentStream(String label) {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, ?, 'ACTIVE', ?, ?, CURRENT_TIMESTAMP)
                """, merchantId, label + "-merchant", "thaap_test_a", uniqueHash());
        jdbcTemplate.update("""
                INSERT INTO customers (id, merchant_id, email_hash, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, customerId, merchantId, uniqueHash());
        jdbcTemplate.update("""
                INSERT INTO payment_intents (id, merchant_id, customer_id, amount, currency,
                    capture_method, status, metadata, created_at, updated_at)
                VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', 'SUCCEEDED', '{}',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, paymentIntentId, merchantId, customerId);
        return new Fixture(new RiskOperatorContext(merchantId, UUID.randomUUID(),
                "rop_" + label, RiskOperatorRole.RISK_MANAGER, "rop_live_a"), paymentIntentId);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    /** Merchant API key hashes are globally unique, so fixtures cannot share one. */
    private String uniqueHash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private record Fixture(RiskOperatorContext context, UUID paymentIntentId) { }
}
