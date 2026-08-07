package com.thaap.payments.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.support.ApplicationRestartHarness;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for the producer-rollout half of {@code C45-OPS-001} and {@code C46-OPS-001}.
 *
 * <p>Both rows describe a producer-version readiness gate governing mixed-version rollout, and
 * for a long time neither could be proven because no such gate existed — the rows' own
 * implementation lines had made it conditional and the condition was never met.
 * {@link DomainEventProducerGate} is that gate, and this suite exercises it the way a rolling
 * deployment does: real application instances started and stopped against one database, each
 * with the configuration a deployment step would give it.
 *
 * <p>Configuration is only meaningful across a restart. Flipping a field on a live bean would
 * prove that the field is read; starting an instance that has the property proves the deployment
 * behaves, which is what these rows are about.
 */
@Testcontainers(disabledWithoutDocker = true)
class DomainEventProducerRolloutPostgreSqlTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private ApplicationRestartHarness harness;
    private JdbcTemplate outsideAnyInstance;
    private UUID merchantId;
    private UUID paymentIntentId;

    @BeforeEach
    void setUp() {
        harness = new ApplicationRestartHarness(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        outsideAnyInstance = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        ConfigurableApplicationContext seed = harness.start();
        seed.getBean(DatabaseCleaner.class).clean();
        seedPaymentStream();
        harness.stop();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    /**
     * The fail-closed half. An instance configured to emit a version the catalogue cannot serve
     * must not start at all. Reporting the mismatch through readiness alone would leave an
     * instance accepting traffic it cannot honour, and the events it wrote in the meantime would
     * be undecodable and permanent.
     */
    @Test
    void anInstanceRefusesToStartWhenTheCatalogueCannotServeTheConfiguredProducerVersion() {
        int unsupported = DomainEventCatalogue.INITIAL_EVENT_VERSION + 1;

        // The refusal surfaces as a bean-creation failure wrapping the gate's own message, so
        // the assertion is on the chain rather than on whichever wrapper Spring chose.
        assertThatThrownBy(() -> harness.start(
                "thaap.domain-events.producer-version=" + unsupported))
                .hasStackTraceContaining("producer version " + unsupported)
                .hasStackTraceContaining("not supported by the catalogue");

        assertThat(eventCount()).describedAs("a refused startup must write nothing").isZero();
    }

    /**
     * Producer off, then on again, across real restarts. Disabling must refuse emission rather
     * than drop it silently, and must leave every publication row an earlier instance committed
     * exactly where it was — a configuration rollback is not a delete.
     */
    @Test
    void producerOffThenOnRefusesEmissionWhileDisabledAndPreservesPendingEvidence() {
        ConfigurableApplicationContext producing = harness.start();
        appendPaymentCreated(producing, DomainEventCatalogue.INITIAL_EVENT_VERSION);
        long eventsAfterFirstInstance = eventCount();
        long pendingAfterFirstInstance = pendingPublicationCount();
        assertThat(eventsAfterFirstInstance).isEqualTo(1);
        assertThat(pendingAfterFirstInstance).isEqualTo(1);
        harness.stop();

        ConfigurableApplicationContext disabled = harness.start(
                "thaap.domain-events.producer-enabled=false");
        assertThat(disabled.getBean(DomainEventProducerHealthIndicator.class).health())
                .satisfies(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
                    assertThat(health.getDetails()).containsEntry("state", "PRODUCER_DISABLED");
                });
        assertThatThrownBy(() -> appendPaymentCreated(disabled,
                DomainEventCatalogue.INITIAL_EVENT_VERSION))
                .isInstanceOf(DomainEventProducerDisabledException.class);

        // Refused, not dropped, and nothing the previous instance committed was touched.
        assertThat(eventCount()).isEqualTo(eventsAfterFirstInstance);
        assertThat(pendingPublicationCount()).isEqualTo(pendingAfterFirstInstance);
        assertThat(streamHeadVersion()).isEqualTo(1L);
        harness.stop();

        ConfigurableApplicationContext reenabled = harness.start();
        assertThat(reenabled.getBean(DomainEventProducerHealthIndicator.class).health()
                .getStatus()).isEqualTo(Status.UP);
        appendPaymentCreated(reenabled, DomainEventCatalogue.INITIAL_EVENT_VERSION);

        assertThat(eventCount()).isEqualTo(eventsAfterFirstInstance + 1);
        assertThat(pendingPublicationCount()).isEqualTo(pendingAfterFirstInstance + 1);
        assertThat(streamHeadVersion())
                .describedAs("the disabled instance must not have burned a stream version")
                .isEqualTo(2L);
    }

    /**
     * The new/old application order. An instance that comes up disabled before any event exists
     * must also leave the database untouched, so the order in which a rollout enables producers
     * cannot change what is durable.
     */
    @Test
    void anInstanceThatStartsDisabledBeforeAnyEventExistsWritesNothing() {
        ConfigurableApplicationContext disabled = harness.start(
                "thaap.domain-events.producer-enabled=false");
        assertThatThrownBy(() -> appendPaymentCreated(disabled,
                DomainEventCatalogue.INITIAL_EVENT_VERSION))
                .isInstanceOf(DomainEventProducerDisabledException.class);
        assertThat(eventCount()).isZero();
        assertThat(pendingPublicationCount()).isZero();
        assertThat(streamHeadCount())
                .describedAs("a refused emission must not allocate a stream head")
                .isZero();
        harness.stop();

        ConfigurableApplicationContext enabled = harness.start();
        appendPaymentCreated(enabled, DomainEventCatalogue.INITIAL_EVENT_VERSION);
        assertThat(eventCount()).isEqualTo(1);
        assertThat(streamHeadVersion()).isEqualTo(1L);
    }

    /**
     * Mixed-version operation. An instance configured for one version must refuse to emit
     * another, before anything is durable — this is the failure the row describes as a rolling
     * old consumer receiving a new unsupported event.
     */
    @Test
    void anEmissionAtAVersionThisDeploymentDoesNotProduceIsRefusedBeforeAnythingIsDurable() {
        ConfigurableApplicationContext producing = harness.start();

        assertThatThrownBy(() -> appendPaymentCreated(producing,
                DomainEventCatalogue.INITIAL_EVENT_VERSION + 1))
                .isInstanceOf(DomainEventProducerVersionException.class);
        assertThat(eventCount()).isZero();
        assertThat(pendingPublicationCount()).isZero();
        assertThat(streamHeadCount()).isZero();

        // The configured version on the same stream still writes, so the refusal is the version
        // boundary and not a broken fixture.
        appendPaymentCreated(producing, DomainEventCatalogue.INITIAL_EVENT_VERSION);
        assertThat(eventCount()).isEqualTo(1);
    }

    private void appendPaymentCreated(ConfigurableApplicationContext context, int version) {
        DomainEventWriter writer = context.getBean(DomainEventWriter.class);
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 1200);
        payload.put("currency", "USD");
        payload.put("captureMethod", "AUTOMATIC");
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .execute(status -> writer.append(new DomainEventCommand(merchantId,
                        DomainStreamType.PAYMENT, paymentIntentId,
                        DomainEventType.PAYMENT_INTENT_CREATED, version,
                        Instant.now().minusSeconds(1), "rollout-correlation", null,
                        "rollout-request", null,
                        new DomainEventActor(DomainEventActorType.SYSTEM, "rollout-producer"),
                        TrafficOrigin.MERCHANT_API, payload)));
    }

    private void seedPaymentStream() {
        merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        paymentIntentId = UUID.randomUUID();
        outsideAnyInstance.update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, 'rollout-merchant', 'ACTIVE', 'thaap_test_a', ?, CURRENT_TIMESTAMP)
                """, merchantId, UUID.randomUUID().toString().replace("-", "").repeat(2));
        outsideAnyInstance.update("""
                INSERT INTO customers (id, merchant_id, email_hash, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, customerId, merchantId,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        outsideAnyInstance.update("""
                INSERT INTO payment_intents (id, merchant_id, customer_id, amount, currency,
                    capture_method, status, metadata, created_at, updated_at)
                VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', 'SUCCEEDED', '{}',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, paymentIntentId, merchantId, customerId);
    }

    private long eventCount() {
        return outsideAnyInstance.queryForObject(
                "SELECT COUNT(*) FROM domain_events", Long.class);
    }

    private long pendingPublicationCount() {
        return outsideAnyInstance.queryForObject("""
                SELECT COUNT(*) FROM event_publications WHERE lifecycle_status = 'PENDING'
                """, Long.class);
    }

    private long streamHeadCount() {
        return outsideAnyInstance.queryForObject(
                "SELECT COUNT(*) FROM event_stream_heads", Long.class);
    }

    private long streamHeadVersion() {
        return outsideAnyInstance.queryForObject("""
                SELECT current_version FROM event_stream_heads WHERE stream_id = ?
                """, Long.class, paymentIntentId);
    }
}
