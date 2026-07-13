package com.thaap.payments.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import com.thaap.payments.idempotency.IdempotencyRecord;
import com.thaap.payments.idempotency.IdempotencyRepository;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.payment.Refund;
import com.thaap.payments.payment.RefundRepository;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.paymentmethod.CardFunding;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.webhook.WebhookDelivery;
import com.thaap.payments.webhook.WebhookDeliveryAttempt;
import com.thaap.payments.webhook.WebhookDeliveryAttemptRepository;
import com.thaap.payments.webhook.WebhookDeliveryRepository;
import com.thaap.payments.webhook.WebhookEndpoint;
import com.thaap.payments.webhook.WebhookEndpointRepository;
import com.thaap.payments.webhook.WebhookEvent;
import com.thaap.payments.webhook.WebhookEventRepository;
import com.thaap.payments.webhook.WebhookEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class DatabaseMigrationTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private AuthorizationRepository authorizationRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private WebhookEndpointRepository webhookEndpointRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private WebhookDeliveryAttemptRepository webhookDeliveryAttemptRepository;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void flywayAppliedSchemaMigrations() {
        var initialSchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1'
                  AND success = TRUE
                """, Integer.class);
        var authorizationSchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '2'
                  AND description = 'add authorizations'
                  AND success = TRUE
                """, Integer.class);
        var fraudLineageSchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '3'
                  AND description = 'add fraud lineage'
                  AND success = TRUE
                """, Integer.class);
        var auditLogSchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '4'
                  AND description = 'add audit logs'
                  AND success = TRUE
                """, Integer.class);
        var ledgerSchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '5'
                  AND description = 'add ledger entries'
                  AND success = TRUE
                """, Integer.class);
        var authorizationVoidSchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '6'
                  AND description = 'add authorization voids'
                  AND success = TRUE
                """, Integer.class);
        var legacyCaptureMethodHardening = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '7'
                  AND description = 'harden legacy capture method semantics'
                  AND success = TRUE
                """, Integer.class);
        var refundLedgerSchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '8'
                  AND description = 'add refunds and refund ledger lineage'
                  AND success = TRUE
                """, Integer.class);
        var webhookDeliverySchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '9'
                  AND description = 'harden webhook delivery execution'
                  AND success = TRUE
                """, Integer.class);
        var replayIdentityRotationSchema = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '17'
                  AND description = 'complete hmac rotation boundaries'
                  AND success = TRUE
                """, Integer.class);
        var webhookSubscriptionConsentRepair = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '25'
                  AND description = 'preserve existing webhook subscription consent'
                  AND success = TRUE
                """, Integer.class);

        assertThat(initialSchema).isEqualTo(1);
        assertThat(authorizationSchema).isEqualTo(1);
        assertThat(fraudLineageSchema).isEqualTo(1);
        assertThat(auditLogSchema).isEqualTo(1);
        assertThat(ledgerSchema).isEqualTo(1);
        assertThat(authorizationVoidSchema).isEqualTo(1);
        assertThat(legacyCaptureMethodHardening).isEqualTo(1);
        assertThat(refundLedgerSchema).isEqualTo(1);
        assertThat(webhookDeliverySchema).isEqualTo(1);
        assertThat(replayIdentityRotationSchema).isEqualTo(1);
        assertThat(webhookSubscriptionConsentRepair).isEqualTo(1);
    }

    @Test
    void tenantTablesAndHotLookupIndexesExist() throws SQLException {
        assertThat(tableNames()).contains(
                "merchants",
                "customers",
                "payment_methods",
                "payment_intents",
                "payment_attempts",
                "authorizations",
                "authorization_voids",
                "idempotency_records",
                "webhook_endpoints",
                "webhook_endpoint_event_subscriptions",
                "webhook_events",
                "webhook_deliveries",
                "webhook_delivery_attempts",
                "fraud_decisions",
                "feature_snapshots",
                "audit_logs",
                "ledger_entries",
                "refunds",
                "fraud_rules",
                "review_cases");

        assertIndexExists("idempotency_records", "uk_idempotency_merchant_key");
        assertIndexExists("idempotency_records", "idx_idempotency_merchant_status");
        assertIndexExists("merchants", "uk_merchants_api_key_hash");
        assertIndexExists("payment_intents", "uk_payment_intents_merchant_id_id");
        assertIndexExists("payment_intents", "idx_payment_intents_merchant_customer");
        assertIndexExists("payment_attempts", "uk_payment_attempts_merchant_id_id");
        assertIndexExists("payment_attempts", "uk_payment_attempts_intent_number");
        assertIndexExists("payment_attempts", "idx_payment_attempts_merchant_created");
        assertIndexExists("authorizations", "uk_authorizations_merchant_id_id");
        assertIndexExists("authorizations", "uk_authorizations_attempt");
        assertIndexExists("authorizations", "uk_authorizations_processor_reference");
        assertIndexExists("authorizations", "idx_authorizations_merchant_created");
        assertIndexExists("authorization_voids", "uk_authorization_voids_authorization");
        assertIndexExists("authorization_voids", "uk_authorization_voids_payment_intent");
        assertIndexExists("authorization_voids", "idx_authorization_voids_merchant_created");
        assertIndexExists("authorization_voids", "idx_authorization_voids_attempt");
        assertIndexExists("payment_methods", "idx_payment_methods_merchant_bin");
        assertIndexExists("webhook_endpoints", "idx_webhook_endpoints_merchant_status");
        assertIndexExists("webhook_endpoints", "uk_webhook_endpoints_signing_secret_hash");
        assertIndexExists("webhook_events", "idx_webhook_events_merchant_created");
        assertIndexExists("webhook_deliveries", "idx_webhook_deliveries_merchant_created");
        assertIndexExists("webhook_deliveries", "idx_webhook_deliveries_merchant_status_next_attempt");
        assertIndexExists("webhook_deliveries", "idx_webhook_deliveries_due");
        assertIndexExists("webhook_deliveries", "idx_webhook_deliveries_expired_lease");
        assertIndexExists("webhook_delivery_attempts", "idx_webhook_attempts_merchant_delivery");
        assertIndexExists("fraud_decisions", "uk_fraud_decisions_merchant_id_id");
        assertIndexExists("fraud_decisions", "uk_fraud_decisions_attempt");
        assertIndexExists("fraud_decisions", "idx_fraud_decisions_merchant_created");
        assertIndexExists("fraud_decisions", "idx_fraud_decisions_merchant_attempt");
        assertIndexExists("feature_snapshots", "uk_feature_snapshots_merchant_id_id");
        assertIndexExists("feature_snapshots", "uk_feature_snapshots_decision");
        assertIndexExists("feature_snapshots", "uk_feature_snapshots_attempt");
        assertIndexExists("feature_snapshots", "idx_feature_snapshots_merchant_created");
        assertIndexExists("audit_logs", "idx_audit_logs_merchant_created");
        assertIndexExists("audit_logs", "idx_audit_logs_merchant_action_created");
        assertIndexExists("audit_logs", "idx_audit_logs_merchant_aggregate");
        assertIndexExists("audit_logs", "idx_audit_logs_request_id");
        assertIndexExists("ledger_entries", "idx_ledger_entries_merchant_created");
        assertIndexExists("ledger_entries", "idx_ledger_entries_merchant_group");
        assertIndexExists("ledger_entries", "idx_ledger_entries_merchant_source");
        assertIndexExists("ledger_entries", "idx_ledger_entries_merchant_payment_intent");
        assertIndexExists("ledger_entries", "idx_ledger_entries_merchant_refund");
        assertIndexExists("ledger_entries", "uk_ledger_entries_source_account");
        assertIndexExists("refunds", "uk_refunds_merchant_id_id");
        assertIndexExists("refunds", "uk_refunds_merchant_id_id_payment_intent");
        assertIndexExists("refunds", "uk_refunds_payment_intent");
        assertIndexExists("refunds", "idx_refunds_merchant_created");
        assertIndexExists("refunds", "idx_refunds_attempt");
        assertIndexExists("fraud_rules", "idx_fraud_rules_ruleset_status");
        assertIndexExists("rule_execution_results", "idx_rule_results_merchant_decision");
        assertIndexExists("manual_review_cases", "idx_manual_reviews_merchant_status_created");
        assertIndexExists("review_cases", "idx_review_cases_merchant_created");

        assertThat(columnNames("merchants")).contains("api_key_prefix", "api_key_hash").doesNotContain("api_key");
        assertThat(columnNames("payment_methods")).contains("card_bin", "card_funding", "card_country");
        assertThat(columnNames("payment_intents")).contains("capture_method", "description", "metadata");
        assertThat(columnNames("payment_attempts")).contains("payment_intent_id", "payment_method_id",
                "idempotency_key", "attempt_number", "fraud_decision_id", "processor_authorization_id",
                "three_ds_status");
        assertThat(columnNames("authorizations")).contains("payment_attempt_id", "processor",
                "processor_reference_id", "processor_response_code", "network_status", "authorized_amount",
                "requested_amount", "currency", "status", "cvc_check", "avs_line1_check",
                "avs_postal_code_check", "three_ds_result", "simulator_outcome_code", "created_at");
        assertThat(columnNames("authorization_voids")).contains("merchant_id", "authorization_id",
                "payment_attempt_id", "payment_intent_id", "status", "reason", "created_at");
        assertThat(columnNames("fraud_decisions")).contains("payment_attempt_id", "risk_score", "risk_level",
                "action", "model_version", "ruleset_version", "latency_ms", "decision_started_at",
                "decision_completed_at", "feature_freshness_status", "max_feature_age_ms", "reason_codes",
                "created_at");
        assertThat(columnNames("feature_snapshots")).contains("fraud_decision_id", "payment_attempt_id",
                "feature_version", "features_json", "computed_at", "oldest_feature_timestamp",
                "max_feature_age_ms", "freshness_status", "stale_feature_names", "missing_feature_names",
                "created_at");
        assertThat(columnNames("audit_logs")).contains("merchant_id", "actor_type", "actor_id", "action",
                "aggregate_type", "aggregate_id", "request_id", "trace_id", "reason", "before_json",
                "after_json", "created_at");
        assertThat(columnNames("ledger_entries")).contains("merchant_id", "transaction_group_id", "source_type",
                "source_id", "payment_intent_id", "refund_id", "account", "direction", "amount", "currency",
                "created_at");
        assertThat(columnNames("refunds")).contains("merchant_id", "payment_intent_id", "payment_attempt_id",
                "authorization_id", "amount", "currency", "status", "created_at");
        assertThat(columnNames("idempotency_records")).contains("operation", "expires_at");
        assertThat(columnNames("webhook_endpoints")).contains(
                "operational_status", "signing_secret_prefix", "signing_secret_hash",
                "signing_secret_ciphertext", "signing_secret_nonce", "signing_secret_key_version")
                .doesNotContain("active");
        assertThat(columnNames("webhook_events")).contains(
                "merchant_id", "event_type", "resource_id", "resource_type", "api_version",
                "payload", "payload_sha256", "occurred_at", "created_at");
        assertThat(columnNames("webhook_deliveries")).contains("attempt_count", "next_attempt_at", "last_attempt_at",
                "delivered_at", "webhook_event_id", "last_error_code", "claim_token", "lease_expires_at")
                .doesNotContain("last_error");
        assertThat(columnNames("webhook_delivery_attempts")).contains(
                "merchant_id", "webhook_delivery_id", "attempt_number", "outcome",
                "http_request_sent", "dispatch_started_at", "response_status", "error_code", "started_at",
                "completed_at", "latency_ms");
    }

    @Test
    void merchantApiKeyIsPersistedAsPrefixAndHashOnly() {
        var rawApiKey = "thaap_test_secret";
        merchantRepository.saveAndFlush(new Merchant("merchant-a", rawApiKey));

        var storedPrefix = jdbcTemplate.queryForObject("""
                SELECT api_key_prefix
                FROM merchants
                WHERE api_key_hash = ?
                """, String.class, SecretHasher.sha256(rawApiKey));
        var storedHash = jdbcTemplate.queryForObject("""
                SELECT api_key_hash
                FROM merchants
                WHERE api_key_hash = ?
                """, String.class, SecretHasher.sha256(rawApiKey));

        assertThat(storedPrefix).isEqualTo(SecretHasher.prefix(rawApiKey));
        assertThat(storedHash).isEqualTo(SecretHasher.sha256(rawApiKey));
        assertThat(storedPrefix).isNotEqualTo(rawApiKey);
        assertThat(storedHash).isNotEqualTo(rawApiKey);
    }

    @Test
    void idempotencyKeyIsUniqueOnlyWithinMerchantScope() {
        var merchantA = merchantRepository.saveAndFlush(new Merchant("merchant-a", "thaap_test_a"));
        var merchantB = merchantRepository.saveAndFlush(new Merchant("merchant-b", "thaap_test_b"));

        idempotencyRepository.saveAndFlush(idempotencyRecord(merchantA.getId(), "shared-key", "request-a"));
        idempotencyRepository.saveAndFlush(idempotencyRecord(merchantB.getId(), "shared-key", "request-b"));

        assertThatThrownBy(() -> idempotencyRepository.saveAndFlush(
                idempotencyRecord(merchantA.getId(), "shared-key", "request-c")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void paymentIntentCustomerForeignKeyRequiresSameMerchantOwnership() {
        var merchantA = merchantRepository.saveAndFlush(new Merchant("merchant-a", "thaap_test_a"));
        var merchantB = merchantRepository.saveAndFlush(new Merchant("merchant-b", "thaap_test_b"));
        var customerA = customerRepository.saveAndFlush(new Customer(merchantA.getId(), "email-hash", null));

        assertThatThrownBy(() -> paymentIntentRepository.saveAndFlush(new PaymentIntent(
                merchantB.getId(), customerA.getId(), 1200, "USD")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void webhookDeliveryEndpointForeignKeyRequiresSameMerchantOwnership() {
        var merchantA = merchantRepository.saveAndFlush(new Merchant("merchant-a", "thaap_test_a"));
        var merchantB = merchantRepository.saveAndFlush(new Merchant("merchant-b", "thaap_test_b"));
        var endpointA = webhookEndpointRepository.saveAndFlush(new WebhookEndpoint(
                merchantA.getId(), "https://merchant-a.example/webhooks"));
        UUID resourceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        String payload = "{\"id\":\"" + eventId + "\"}";
        WebhookEvent eventA = webhookEventRepository.saveAndFlush(new WebhookEvent(
                eventId, merchantA.getId(), WebhookEventType.PAYMENT_INTENT_CREATED,
                resourceId, payload, SecretHasher.sha256(payload), occurredAt));
        WebhookDelivery deliveryA = webhookDeliveryRepository.saveAndFlush(new WebhookDelivery(
                merchantA.getId(), endpointA.getId(), eventA.getId(),
                eventA.getEventType(), resourceId, occurredAt));
        WebhookEndpoint secondEndpointA = webhookEndpointRepository.saveAndFlush(new WebhookEndpoint(
                merchantA.getId(), "https://merchant-a.example/other-webhooks"));

        assertThatThrownBy(() -> webhookDeliveryRepository.saveAndFlush(new WebhookDelivery(
                merchantB.getId(), endpointA.getId(), eventA.getId(),
                eventA.getEventType(), resourceId, occurredAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> webhookDeliveryRepository.saveAndFlush(new WebhookDelivery(
                merchantA.getId(), endpointA.getId(), eventA.getId(),
                eventA.getEventType(), resourceId, occurredAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> webhookDeliveryRepository.saveAndFlush(new WebhookDelivery(
                merchantA.getId(), secondEndpointA.getId(), eventA.getId(),
                WebhookEventType.REFUND_CREATED.wireValue(), resourceId, occurredAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> webhookDeliveryAttemptRepository.saveAndFlush(new WebhookDeliveryAttempt(
                merchantB.getId(), deliveryA.getId(), 1, occurredAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ledgerEntrySourceRequiresSameMerchantPaymentIntentAndUniqueCaptureAccount() {
        var merchantA = merchantRepository.saveAndFlush(new Merchant("merchant-a", "thaap_test_a"));
        var merchantB = merchantRepository.saveAndFlush(new Merchant("merchant-b", "thaap_test_b"));
        var customerA = customerRepository.saveAndFlush(new Customer(merchantA.getId(), "email-hash", null));
        var paymentIntentA = paymentIntentRepository.saveAndFlush(new PaymentIntent(
                merchantA.getId(), customerA.getId(), 1200, "USD"));

        insertLedgerEntry(merchantA.getId(), paymentIntentA.getId(), "CUSTOMER_RECEIVABLE", "DEBIT");

        assertThatThrownBy(() -> insertLedgerEntry(merchantA.getId(), UUID.randomUUID(),
                "MERCHANT_PAYABLE", "CREDIT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedgerEntry(merchantB.getId(), paymentIntentA.getId(),
                "MERCHANT_PAYABLE", "CREDIT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedgerEntry(merchantA.getId(), paymentIntentA.getId(),
                "CUSTOMER_RECEIVABLE", "DEBIT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedgerEntry(merchantA.getId(), paymentIntentA.getId(),
                "MERCHANT_PAYABLE", "DEBIT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refundAndRefundLedgerConstraintsEnforceTenantAndObjectLineage() {
        Merchant merchantA = merchantRepository.saveAndFlush(new Merchant("merchant-a", "thaap_refund_a"));
        Merchant merchantB = merchantRepository.saveAndFlush(new Merchant("merchant-b", "thaap_refund_b"));
        Customer customerA = customerRepository.saveAndFlush(new Customer(merchantA.getId(), "refund-email-a", null));
        Customer customerB = customerRepository.saveAndFlush(new Customer(merchantB.getId(), "refund-email-b", null));
        PaymentIntent paymentIntentA = paymentIntentRepository.saveAndFlush(new PaymentIntent(
                merchantA.getId(), customerA.getId(), 1200, "USD"));
        PaymentIntent otherPaymentIntentA = paymentIntentRepository.saveAndFlush(new PaymentIntent(
                merchantA.getId(), customerA.getId(), 1200, "USD"));
        PaymentIntent paymentIntentB = paymentIntentRepository.saveAndFlush(new PaymentIntent(
                merchantB.getId(), customerB.getId(), 1200, "USD"));
        PaymentMethod paymentMethodA = paymentMethodRepository.saveAndFlush(new PaymentMethod(
                merchantA.getId(), customerA.getId(), "tok_a", "fingerprint-a", "499999", "0001",
                CardFunding.CREDIT, "US"));
        PaymentAttempt paymentAttemptA = paymentAttemptRepository.saveAndFlush(new PaymentAttempt(
                merchantA.getId(), paymentIntentA.getId(), paymentMethodA.getId(), "refund-attempt-a", 1));
        Authorization authorizationA = authorizationRepository.saveAndFlush(approvedAuthorization(
                merchantA.getId(), paymentAttemptA.getId(), "refund-auth-a"));
        Refund refundA = refundRepository.saveAndFlush(new Refund(merchantA.getId(), paymentIntentA.getId(),
                paymentAttemptA.getId(), authorizationA.getId(), 1200, "USD"));

        assertThatThrownBy(() -> refundRepository.saveAndFlush(new Refund(merchantA.getId(), paymentIntentA.getId(),
                paymentAttemptA.getId(), authorizationA.getId(), 1200, "USD")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> refundRepository.saveAndFlush(new Refund(merchantA.getId(),
                otherPaymentIntentA.getId(), paymentAttemptA.getId(), authorizationA.getId(), 1200, "USD")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> refundRepository.saveAndFlush(new Refund(merchantB.getId(), paymentIntentB.getId(),
                paymentAttemptA.getId(), authorizationA.getId(), 1200, "USD")))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertRefundLedgerEntry(merchantA.getId(), paymentIntentA.getId(), refundA.getId(),
                "MERCHANT_PAYABLE", "DEBIT");
        assertThatThrownBy(() -> insertRefundLedgerEntry(merchantA.getId(), paymentIntentA.getId(), refundA.getId(),
                "CUSTOMER_RECEIVABLE", "DEBIT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRefundLedgerEntry(merchantA.getId(), otherPaymentIntentA.getId(),
                refundA.getId(), "CUSTOMER_RECEIVABLE", "CREDIT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legacyPlaceholderFraudDecisionsSurviveFraudLineageMigrationWithoutFakeAttemptLineage() {
        String databaseName = "legacy_fraud_lineage_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure()
                .dataSource(upgradeDataSource)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        UUID merchantId = UUID.randomUUID();
        UUID fraudDecisionId = UUID.randomUUID();

        upgradeJdbcTemplate.update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, 'legacy-merchant', 'ACTIVE', 'thaap_', 'legacy-hash', CURRENT_TIMESTAMP)
                """, merchantId);
        upgradeJdbcTemplate.update("""
                INSERT INTO fraud_decisions (id, merchant_id, action, created_at)
                VALUES (?, ?, 'ALLOW', CURRENT_TIMESTAMP)
                """, fraudDecisionId, merchantId);

        Flyway.configure()
                .dataSource(upgradeDataSource)
                .locations("classpath:db/migration")
                .target("3")
                .load()
                .migrate();

        Map<String, Object> legacyDecision = upgradeJdbcTemplate.queryForMap("""
                SELECT payment_attempt_id, risk_score, risk_level, ruleset_version, latency_ms,
                       feature_freshness_status, max_feature_age_ms, reason_codes
                FROM fraud_decisions
                WHERE id = ?
                """, fraudDecisionId);
        assertThat(legacyDecision.get("payment_attempt_id")).isNull();
        assertThat(legacyDecision.get("risk_score").toString()).isEqualTo("0.0000");
        assertThat(legacyDecision.get("risk_level")).isEqualTo("LOW");
        assertThat(legacyDecision.get("ruleset_version")).isEqualTo("legacy_placeholder_v1");
        assertThat(legacyDecision.get("latency_ms")).isEqualTo(0L);
        assertThat(legacyDecision.get("feature_freshness_status")).isEqualTo("MISSING");
        assertThat(legacyDecision.get("max_feature_age_ms")).isEqualTo(0L);
        assertThat(legacyDecision.get("reason_codes")).isEqualTo("[\"RULES_NOT_ENABLED\"]");
        assertThat(upgradeJdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_snapshots", Integer.class))
                .isZero();
    }

    @Test
    void legacyOpenAutomaticRowsPreserveExplicitCaptureBehaviorDuringChapter17Migration() {
        String databaseName = "legacy_capture_method_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure()
                .dataSource(upgradeDataSource)
                .locations("classpath:db/migration")
                .target("5")
                .load()
                .migrate();
        JdbcTemplate upgradeJdbcTemplate = new JdbcTemplate(upgradeDataSource);
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        upgradeJdbcTemplate.update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, 'legacy-merchant', 'ACTIVE', 'thaap_', 'legacy-capture-hash', CURRENT_TIMESTAMP)
                """, merchantId);
        upgradeJdbcTemplate.update("""
                INSERT INTO customers (id, merchant_id, email_hash, created_at)
                VALUES (?, ?, 'legacy-email-hash', CURRENT_TIMESTAMP)
                """, customerId, merchantId);
        List<String> openStatuses = List.of(
                "REQUIRES_PAYMENT_METHOD",
                "REQUIRES_CONFIRMATION",
                "REQUIRES_FRAUD_CHECK",
                "REQUIRES_ACTION",
                "PROCESSING",
                "AUTHORIZED",
                "REQUIRES_CAPTURE");
        List<String> terminalStatuses = List.of("CAPTURED", "CANCELED", "REFUNDED", "DISPUTED", "FAILED");
        Map<String, UUID> paymentIntentIdsByStatus = new LinkedHashMap<>();
        for (String status : Stream.concat(openStatuses.stream(), terminalStatuses.stream()).toList()) {
            UUID paymentIntentId = UUID.randomUUID();
            paymentIntentIdsByStatus.put(status, paymentIntentId);
            upgradeJdbcTemplate.update("""
                    INSERT INTO payment_intents (
                        id, merchant_id, customer_id, amount, currency, capture_method, status,
                        metadata, created_at, updated_at
                    )
                    VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', ?, '{}',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, paymentIntentId, merchantId, customerId, status);
        }

        Flyway.configure()
                .dataSource(upgradeDataSource)
                .locations("classpath:db/migration")
                .target("7")
                .load()
                .migrate();

        for (String status : openStatuses) {
            assertThat(captureMethod(upgradeJdbcTemplate, paymentIntentIdsByStatus.get(status)))
                    .as("legacy open status %s", status)
                    .isEqualTo("MANUAL");
        }
        for (String status : terminalStatuses) {
            assertThat(captureMethod(upgradeJdbcTemplate, paymentIntentIdsByStatus.get(status)))
                    .as("legacy terminal status %s", status)
                    .isEqualTo("AUTOMATIC");
        }
    }

    @Test
    void chapter18MigrationBackfillsCaptureLedgerLineageWithoutInventingRefundTruth() {
        UpgradeDatabase upgrade = createUpgradeDatabase("chapter18_capture_backfill");
        migrate(upgrade.dataSource(), "7");
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        insertUpgradeMerchantCustomerAndPaymentIntent(upgrade.jdbcTemplate(), merchantId, customerId,
                paymentIntentId, "CAPTURED");
        insertLegacyCaptureLedgerEntry(upgrade.jdbcTemplate(), merchantId, paymentIntentId,
                "CUSTOMER_RECEIVABLE", "DEBIT");
        insertLegacyCaptureLedgerEntry(upgrade.jdbcTemplate(), merchantId, paymentIntentId,
                "MERCHANT_PAYABLE", "CREDIT");

        migrate(upgrade.dataSource(), "8");

        List<Map<String, Object>> entries = upgrade.jdbcTemplate().queryForList("""
                SELECT source_id, payment_intent_id, refund_id
                FROM ledger_entries
                ORDER BY account
                """);
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.get("source_id")).isEqualTo(paymentIntentId);
            assertThat(entry.get("payment_intent_id")).isEqualTo(paymentIntentId);
            assertThat(entry.get("refund_id")).isNull();
        });
        assertThat(upgrade.jdbcTemplate().queryForObject("SELECT COUNT(*) FROM refunds", Integer.class)).isZero();
    }

    @Test
    void chapter18MigrationRejectsLegacyRefundedPaymentIntents() {
        UpgradeDatabase upgrade = createUpgradeDatabase("chapter18_legacy_refund_guard");
        migrate(upgrade.dataSource(), "7");
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        insertUpgradeMerchantCustomerAndPaymentIntent(upgrade.jdbcTemplate(), merchantId, customerId,
                paymentIntentId, "REFUNDED");

        assertThatThrownBy(() -> migrate(upgrade.dataSource(), "8"))
                .hasMessageContaining("V8__add_refunds_and_refund_ledger_lineage.sql")
                .hasStackTraceContaining("ck_chapter18_no_legacy_refunded_payment_intents");
    }

    @Test
    void v9MigrationBackfillsImmutableEventsAndRequiresLegacySecretRotation() throws Exception {
        UpgradeDatabase upgrade = createUpgradeDatabase("v9_webhook_backfill");
        migrate(upgrade.dataSource(), "8");
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID disabledEndpointId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID disabledDeliveryId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        upgrade.jdbcTemplate().update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, 'legacy-webhook-merchant', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                """, merchantId, "legacy-webhook-" + merchantId);
        upgrade.jdbcTemplate().update("""
                INSERT INTO webhook_endpoints (
                    id, merchant_id, url, active, signing_secret_prefix,
                    signing_secret_hash, created_at
                )
                VALUES (?, ?, 'https://legacy.example/webhooks', TRUE, 'whsec_', ?, CURRENT_TIMESTAMP)
                """, endpointId, merchantId, "legacy-signing-hash-" + endpointId);
        upgrade.jdbcTemplate().update("""
                INSERT INTO webhook_endpoints (
                    id, merchant_id, url, active, signing_secret_prefix,
                    signing_secret_hash, created_at
                )
                VALUES (?, ?, 'https://disabled.example/webhooks', FALSE, 'whsec_', ?, CURRENT_TIMESTAMP)
                """, disabledEndpointId, merchantId, "legacy-signing-hash-" + disabledEndpointId);
        upgrade.jdbcTemplate().update("""
                INSERT INTO webhook_deliveries (
                    id, merchant_id, webhook_endpoint_id, event_type, resource_id, status,
                    attempt_count, created_at
                )
                VALUES (?, ?, ?, 'payment_intent.created', ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                """, deliveryId, merchantId, endpointId, resourceId);
        upgrade.jdbcTemplate().update("""
                INSERT INTO webhook_deliveries (
                    id, merchant_id, webhook_endpoint_id, event_type, resource_id, status,
                    attempt_count, created_at
                )
                VALUES (?, ?, ?, 'payment_intent.created', ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                """, disabledDeliveryId, merchantId, disabledEndpointId, UUID.randomUUID());

        migrate(upgrade.dataSource(), "9");

        assertThat(upgrade.jdbcTemplate().queryForObject("""
                SELECT operational_status
                FROM webhook_endpoints
                WHERE id = ?
                """, String.class, endpointId)).isEqualTo("REQUIRES_SECRET_ROTATION");
        assertThat(upgrade.jdbcTemplate().queryForObject("""
                SELECT operational_status
                FROM webhook_endpoints
                WHERE id = ?
                """, String.class, disabledEndpointId)).isEqualTo("DISABLED");
        assertThat(upgrade.jdbcTemplate().queryForObject("""
                SELECT COUNT(*)
                FROM webhook_endpoint_event_subscriptions
                WHERE webhook_endpoint_id = ?
                """, Integer.class, endpointId)).isEqualTo(5);
        Map<String, Object> event = upgrade.jdbcTemplate().queryForMap("""
                SELECT id, merchant_id, event_type, resource_id, resource_type,
                       api_version, payload, payload_sha256
                FROM webhook_events
                WHERE id = ?
                """, deliveryId);
        assertThat(event.get("merchant_id")).isEqualTo(merchantId);
        assertThat(event.get("event_type")).isEqualTo("payment_intent.created");
        assertThat(event.get("resource_id")).isEqualTo(resourceId);
        assertThat(event.get("resource_type")).isEqualTo("PAYMENT_INTENT");
        assertThat(event.get("api_version")).isEqualTo("v1");
        JsonNode payload = OBJECT_MAPPER.readTree(event.get("payload").toString());
        assertThat(payload.get("id").asText()).isEqualTo(deliveryId.toString());
        assertThat(payload.get("type").asText()).isEqualTo("payment_intent.created");
        assertThat(payload.get("apiVersion").asText()).isEqualTo("v1");
        assertThat(payload.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(payload.path("data").path("object").get("id").asText()).isEqualTo(resourceId.toString());
        assertThat(payload.path("data").path("object").get("type").asText()).isEqualTo("PAYMENT_INTENT");
        assertThat(event.get("payload_sha256"))
                .isEqualTo(SecretHasher.sha256(event.get("payload").toString()));
        assertThat(upgrade.jdbcTemplate().queryForObject("""
                SELECT webhook_event_id
                FROM webhook_deliveries
                WHERE id = ?
                """, UUID.class, deliveryId)).isEqualTo(deliveryId);
        assertThat(upgrade.jdbcTemplate().queryForObject("""
                SELECT COUNT(*)
                FROM webhook_deliveries
                WHERE webhook_event_id = id
                """, Integer.class)).isEqualTo(2);
        assertThat(upgrade.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM webhook_events", Integer.class)).isEqualTo(2);
        Map<String, Object> queued = upgrade.jdbcTemplate().queryForMap("""
                SELECT status, next_attempt_at, last_error_code
                FROM webhook_deliveries
                WHERE id = ?
                """, deliveryId);
        assertThat(queued)
                .containsEntry("status", "PENDING")
                .containsEntry("next_attempt_at", null)
                .containsEntry("last_error_code", null);
        Map<String, Object> disabled = upgrade.jdbcTemplate().queryForMap("""
                SELECT status, next_attempt_at, delivered_at, last_error_code
                FROM webhook_deliveries
                WHERE id = ?
                """, disabledDeliveryId);
        assertThat(disabled)
                .containsEntry("status", "EXHAUSTED")
                .containsEntry("last_error_code", "ENDPOINT_DISABLED");
        assertThat(disabled.get("next_attempt_at")).isNull();
        assertThat(disabled.get("delivered_at")).isNull();
        assertThat(upgrade.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_attempts", Integer.class)).isZero();
    }

    @Test
    void v9MigrationRejectsLegacyExecutionStateWithoutAttemptEvidence() {
        UpgradeDatabase upgrade = createUpgradeDatabase("v9_webhook_execution_guard");
        migrate(upgrade.dataSource(), "8");
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        upgrade.jdbcTemplate().update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, 'legacy-webhook-guard', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                """, merchantId, "legacy-webhook-guard-" + merchantId);
        upgrade.jdbcTemplate().update("""
                INSERT INTO webhook_endpoints (
                    id, merchant_id, url, active, signing_secret_prefix,
                    signing_secret_hash, created_at
                )
                VALUES (?, ?, 'https://legacy.example/webhooks', TRUE, 'whsec_', ?, CURRENT_TIMESTAMP)
                """, endpointId, merchantId, "legacy-signing-hash-" + endpointId);
        upgrade.jdbcTemplate().update("""
                INSERT INTO webhook_deliveries (
                    id, merchant_id, webhook_endpoint_id, event_type, resource_id, status,
                    attempt_count, next_attempt_at, created_at
                )
                VALUES
                    (?, ?, ?, 'payment_intent.created', ?, 'PENDING', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (?, ?, ?, 'payment_intent.created', ?, 'SUCCEEDED', 1, NULL, CURRENT_TIMESTAMP),
                    (?, ?, ?, 'payment_intent.created', ?, 'FAILED', 2, NULL, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(), merchantId, endpointId, UUID.randomUUID(),
                UUID.randomUUID(), merchantId, endpointId, UUID.randomUUID(),
                UUID.randomUUID(), merchantId, endpointId, UUID.randomUUID());

        assertThatThrownBy(() -> migrate(upgrade.dataSource(), "9"))
                .hasStackTraceContaining(
                        "V9 cannot migrate unsupported legacy webhook delivery execution state")
                .hasStackTraceContaining("3 row(s)");
    }

    @Test
    void v9MigrationRejectsUnsupportedLegacyEventTypesBeforeCreatingSchemaTruth() {
        UpgradeDatabase upgrade = createUpgradeDatabase("v9_webhook_event_type_guard");
        migrate(upgrade.dataSource(), "8");
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        upgrade.jdbcTemplate().update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, 'legacy-webhook-event-guard', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                """, merchantId, "legacy-webhook-event-guard-" + merchantId);
        upgrade.jdbcTemplate().update("""
                INSERT INTO webhook_endpoints (
                    id, merchant_id, url, active, signing_secret_prefix,
                    signing_secret_hash, created_at
                )
                VALUES (?, ?, 'https://legacy.example/webhooks', TRUE, 'whsec_', ?, CURRENT_TIMESTAMP)
                """, endpointId, merchantId, "legacy-signing-hash-" + endpointId);
        upgrade.jdbcTemplate().update("""
                INSERT INTO webhook_deliveries (
                    id, merchant_id, webhook_endpoint_id, event_type, resource_id, status,
                    attempt_count, created_at
                )
                VALUES (?, ?, ?, 'legacy.unknown', ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), merchantId, endpointId, UUID.randomUUID());

        assertThatThrownBy(() -> migrate(upgrade.dataSource(), "9"))
                .hasStackTraceContaining("V9 cannot migrate unsupported legacy webhook event types")
                .hasStackTraceContaining("1 row(s)");
        assertThat(upgrade.jdbcTemplate().queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_name = 'webhook_events'
                """, Integer.class)).isZero();
        assertThat(upgrade.jdbcTemplate().queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'webhook_endpoints'
                  AND column_name = 'operational_status'
                """, Integer.class)).isZero();
    }

    private String captureMethod(JdbcTemplate source, UUID paymentIntentId) {
        return source.queryForObject("""
                SELECT capture_method
                FROM payment_intents
                WHERE id = ?
                """, String.class, paymentIntentId);
    }

    private Set<String> tableNames() throws SQLException {
        var names = new HashSet<String>();
        try (var connection = dataSource.getConnection();
                var tables = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Set<String> indexNames(String tableName) throws SQLException {
        var names = new HashSet<String>();
        try (var connection = dataSource.getConnection();
                var indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                var indexName = indexes.getString("INDEX_NAME");
                if (indexName != null) {
                    names.add(indexName.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private Set<String> columnNames(String tableName) throws SQLException {
        var names = new HashSet<String>();
        try (var connection = dataSource.getConnection();
                var columns = connection.getMetaData().getColumns(null, null, tableName, "%")) {
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private void assertIndexExists(String tableName, String indexName) throws SQLException {
        assertThat(indexNames(tableName))
                .anySatisfy(actual -> assertThat(actual).startsWith(indexName));
    }

    private void insertLedgerEntry(UUID merchantId, UUID sourceId, String account, String direction) {
        jdbcTemplate.update("""
                INSERT INTO ledger_entries (
                    id,
                    merchant_id,
                    transaction_group_id,
                    source_type,
                    source_id,
                    payment_intent_id,
                    refund_id,
                    account,
                    direction,
                    amount,
                    currency,
                    created_at
                )
                VALUES (?, ?, ?, 'PAYMENT_INTENT_CAPTURE', ?, ?, NULL, ?, ?, 1200, 'USD', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), merchantId, UUID.randomUUID(), sourceId, sourceId, account, direction);
    }

    private void insertRefundLedgerEntry(UUID merchantId, UUID paymentIntentId, UUID refundId, String account,
            String direction) {
        jdbcTemplate.update("""
                INSERT INTO ledger_entries (
                    id, merchant_id, transaction_group_id, source_type, source_id, payment_intent_id, refund_id,
                    account, direction, amount, currency, created_at
                )
                VALUES (?, ?, ?, 'REFUND', ?, ?, ?, ?, ?, 1200, 'USD', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), merchantId, UUID.randomUUID(), refundId, paymentIntentId, refundId,
                account, direction);
    }

    private Authorization approvedAuthorization(UUID merchantId, UUID paymentAttemptId, String reference) {
        return new Authorization(merchantId, paymentAttemptId, "simulator", reference, "APPROVED",
                NetworkStatus.APPROVED, 1200, 1200, "USD", AuthorizationStatus.APPROVED,
                VerificationResult.PASS, VerificationResult.PASS, VerificationResult.PASS,
                ThreeDsResult.NOT_REQUIRED, "APPROVED");
    }

    private UpgradeDatabase createUpgradeDatabase(String prefix) {
        String databaseName = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        SingleConnectionDataSource upgradeDataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa", "", true);
        return new UpgradeDatabase(upgradeDataSource, new JdbcTemplate(upgradeDataSource));
    }

    private void migrate(DataSource upgradeDataSource, String target) {
        Flyway.configure()
                .dataSource(upgradeDataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void insertUpgradeMerchantCustomerAndPaymentIntent(JdbcTemplate source, UUID merchantId,
            UUID customerId, UUID paymentIntentId, String status) {
        source.update("""
                INSERT INTO merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, 'upgrade-merchant', 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                """, merchantId, "upgrade-hash-" + paymentIntentId);
        source.update("""
                INSERT INTO customers (id, merchant_id, email_hash, created_at)
                VALUES (?, ?, 'upgrade-email-hash', CURRENT_TIMESTAMP)
                """, customerId, merchantId);
        source.update("""
                INSERT INTO payment_intents (
                    id, merchant_id, customer_id, amount, currency, capture_method, status,
                    metadata, created_at, updated_at
                )
                VALUES (?, ?, ?, 1200, 'USD', 'AUTOMATIC', ?, '{}',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, paymentIntentId, merchantId, customerId, status);
    }

    private void insertLegacyCaptureLedgerEntry(JdbcTemplate source, UUID merchantId, UUID paymentIntentId,
            String account, String direction) {
        source.update("""
                INSERT INTO ledger_entries (
                    id, merchant_id, transaction_group_id, source_type, source_id,
                    account, direction, amount, currency, created_at
                )
                VALUES (?, ?, ?, 'PAYMENT_INTENT_CAPTURE', ?, ?, ?, 1200, 'USD', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), merchantId, paymentIntentId, paymentIntentId, account, direction);
    }

    private IdempotencyRecord idempotencyRecord(UUID merchantId, String key, String requestHash) {
        return new IdempotencyRecord(merchantId, key, "test.operation", requestHash,
                Instant.now().plus(1, ChronoUnit.DAYS));
    }

    private record UpgradeDatabase(DataSource dataSource, JdbcTemplate jdbcTemplate) {
    }
}
