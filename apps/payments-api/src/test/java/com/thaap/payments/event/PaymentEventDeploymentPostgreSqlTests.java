package com.thaap.payments.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.eventpublication.EventPublicationPublisher;
import com.thaap.payments.eventpublication.KafkaDomainEventSender;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.time.DatabaseInstant;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof for {@code C45-OPS-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}).
 *
 * <p><strong>What this row asked for that does not exist.</strong> The row's implementation line
 * says a "producer-version readiness gate" would be added "only if integration requires new
 * constraints". It did not: there is no producer-version configuration anywhere in the codebase,
 * and {@link DomainEventCatalogue} carries a single {@code INITIAL_EVENT_VERSION}. So the
 * mixed-producer-version partition has no mechanism behind it to exercise, and what is proven
 * here instead is the boundary that does exist — an unsupported version is refused at the writer
 * before anything is durable. That is a narrower claim than the row promises and the status
 * reflects it.
 *
 * <p>Publication is disabled in the {@code test} profile, which makes the broker-absent partition
 * the default state of this suite rather than a special case: the publisher and Kafka sender
 * beans are absent from the context, and every assertion about pending evidence is made with no
 * broker anywhere in the picture.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.event-publication.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class PaymentEventDeploymentPostgreSqlTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    /**
     * A broker outage must never roll back a payment API success. The publication row is the
     * durable promise that the event will reach Kafka eventually, and it is written in the same
     * transaction as the payment; Kafka itself is outside that transaction by design.
     */
    @Test
    void withNoBrokerThePaymentApiSucceedsAndPublicationEvidenceAccumulatesPending()
            throws Exception {
        assertThat(applicationContext.getBeanNamesForType(EventPublicationPublisher.class))
                .describedAs("the publisher must be absent, or this is not a broker-absent test")
                .isEmpty();
        assertThat(applicationContext.getBeanNamesForType(KafkaDomainEventSender.class)).isEmpty();

        MerchantAccess merchant = createMerchant("payment-deployment-broker-absent");
        UUID customerId = createCustomer(merchant, "broker-absent@example.test");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "broker-absent-method");
        createConfirmedPayment(merchant, customerId, paymentMethodId, "broker-absent");

        long events = count("domain_events");
        assertThat(events).isPositive();
        assertThat(count("event_publications")).isEqualTo(events);
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT lifecycle_status FROM event_publications", String.class))
                .containsExactly("PENDING");
    }

    /**
     * Disabling the producer is a configuration rollback, not a delete. Pending work must still
     * be there afterwards, and the schema must refuse to let anything remove it.
     */
    @Test
    void pendingPublicationEvidenceSurvivesAndCannotBeDeletedOrAdvancedOutOfBand()
            throws Exception {
        MerchantAccess merchant = createMerchant("payment-deployment-pending");
        UUID customerId = createCustomer(merchant, "pending@example.test");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "pending-method");
        createConfirmedPayment(merchant, customerId, paymentMethodId, "pending");

        long pendingBefore = count("event_publications");
        assertThat(pendingBefore).isPositive();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM event_publications"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("event publication evidence cannot be deleted");
        // The status column is closed to PENDING at this schema version, so a deployment cannot
        // mark work published without the publisher having done it.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE event_publications SET lifecycle_status = 'PUBLISHED'"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("event_publications")).isEqualTo(pendingBefore);
    }

    /**
     * The one version boundary that does exist: an event version the catalogue does not support
     * is refused before anything is durable. This is what stands in for the mixed-producer-version
     * partition the row describes, because no producer-version gate was ever built.
     */
    @Test
    void anUnsupportedCatalogueVersionIsRefusedBeforeAnythingBecomesDurable() throws Exception {
        MerchantAccess merchant = createMerchant("payment-deployment-version");
        UUID customerId = createCustomer(merchant, "version@example.test");
        UUID paymentIntentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payment_intents (id, merchant_id, customer_id, amount, currency,
                    capture_method, status, metadata, created_at, updated_at)
                VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', 'REQUIRES_CONFIRMATION', '{}',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, paymentIntentId, merchant.id(), customerId);

        long eventsBefore = count("domain_events");
        long publicationsBefore = count("event_publications");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status -> eventWriter.append(
                command(merchant.id(), paymentIntentId,
                        DomainEventCatalogue.INITIAL_EVENT_VERSION + 1))))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("domain_events")).isEqualTo(eventsBefore);
        assertThat(count("event_publications")).isEqualTo(publicationsBefore);

        // The supported version on the same stream still writes, so the refusal above is the
        // version boundary and not a broken fixture.
        DomainEventWriteResult accepted = transaction.execute(status -> eventWriter.append(
                command(merchant.id(), paymentIntentId,
                        DomainEventCatalogue.INITIAL_EVENT_VERSION)));
        assertThat(accepted).isNotNull();
        assertThat(count("domain_events")).isEqualTo(eventsBefore + 1);
        assertThat(count("event_publications")).isEqualTo(publicationsBefore + 1);
    }

    /**
     * A realistic retained upgrade must carry payment history forward and fabricate no events for
     * it. Backfilled events would be a payment stream nothing actually emitted, and every
     * downstream projection and dataset in Phases 5 onward treats those streams as source truth.
     */
    @Test
    void aRetainedV64UpgradePreservesPaymentHistoryWithoutBackfillingEvents() {
        String schema = "payment_retained_" + UUID.randomUUID().toString().replace("-", "");
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
                    VALUES (?, 'retained-payment-merchant', 'ACTIVE', 'retained_', ?,
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

            Flyway.configure().dataSource(retainedDataSource).schemas(schema)
                    .defaultSchema(schema).locations("classpath:db/migration")
                    .load().migrate();

            assertThat(retained.queryForObject("SELECT status FROM " + schema
                    + ".payment_intents WHERE id = ?", String.class, paymentIntentId))
                    .isEqualTo("SUCCEEDED");
            assertThat(retained.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".domain_events", Integer.class)).isZero();
            assertThat(retained.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".event_publications", Integer.class)).isZero();
            assertThat(retained.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".event_stream_heads", Integer.class)).isZero();

            // Repeated startup is the normal condition of a rolling deployment, so migrating
            // again must be a no-op rather than a second pass over retained data.
            Flyway.configure().dataSource(retainedDataSource).schemas(schema)
                    .defaultSchema(schema).locations("classpath:db/migration")
                    .load().migrate();
            assertThat(retained.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".domain_events", Integer.class)).isZero();
            assertThat(retained.queryForObject("SELECT COUNT(*) FROM " + schema
                    + ".flyway_schema_history WHERE success = FALSE", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private DomainEventCommand command(UUID merchantId, UUID paymentIntentId, int version) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 1200);
        payload.put("currency", "USD");
        payload.put("captureMethod", "AUTOMATIC");
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, paymentIntentId,
                DomainEventType.PAYMENT_INTENT_CREATED, version,
                DatabaseInstant.now().minusSeconds(1), "deployment-correlation", null,
                "deployment-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM, "deployment-producer"),
                TrafficOrigin.MERCHANT_API, payload);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String key)
            throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"cardNumber\":\"4242424242424242\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private void createConfirmedPayment(MerchantAccess merchant, UUID customerId,
            UUID paymentMethodId, String key) throws Exception {
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-intent")
                        .contentType(APPLICATION_JSON).content("{\"customerId\":\"" + customerId
                                + "\",\"amount\":1200,\"currency\":\"USD\","
                                + "\"captureMethod\":\"AUTOMATIC\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/payment_intents/{id}/confirm",
                        UUID.fromString(intent.get("id").asText()))
                        .header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key + "-confirm")
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}"))
                .andExpect(status().isCreated());
    }

    private record MerchantAccess(UUID id, String apiKey) { }
}
