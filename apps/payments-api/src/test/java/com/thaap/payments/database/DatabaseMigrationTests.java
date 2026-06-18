package com.thaap.payments.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.idempotency.IdempotencyRecord;
import com.thaap.payments.idempotency.IdempotencyRepository;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.webhook.WebhookDelivery;
import com.thaap.payments.webhook.WebhookDeliveryRepository;
import com.thaap.payments.webhook.WebhookEndpoint;
import com.thaap.payments.webhook.WebhookEndpointRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class DatabaseMigrationTests {

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
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private WebhookEndpointRepository webhookEndpointRepository;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

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

        assertThat(initialSchema).isEqualTo(1);
        assertThat(authorizationSchema).isEqualTo(1);
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
                "idempotency_records",
                "webhook_endpoints",
                "webhook_deliveries",
                "fraud_decisions",
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
        assertIndexExists("payment_methods", "idx_payment_methods_merchant_bin");
        assertIndexExists("webhook_endpoints", "idx_webhook_endpoints_merchant_active");
        assertIndexExists("webhook_endpoints", "uk_webhook_endpoints_signing_secret_hash");
        assertIndexExists("webhook_deliveries", "idx_webhook_deliveries_merchant_created");
        assertIndexExists("webhook_deliveries", "idx_webhook_deliveries_merchant_status_next_attempt");
        assertIndexExists("fraud_decisions", "idx_fraud_decisions_merchant_created");
        assertIndexExists("fraud_rules", "idx_fraud_rules_merchant_created");
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
        assertThat(columnNames("idempotency_records")).contains("operation", "expires_at");
        assertThat(columnNames("webhook_endpoints")).contains("signing_secret_prefix", "signing_secret_hash");
        assertThat(columnNames("webhook_deliveries")).contains("attempt_count", "next_attempt_at", "last_attempt_at",
                "delivered_at", "last_error");
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

        assertThatThrownBy(() -> webhookDeliveryRepository.saveAndFlush(new WebhookDelivery(
                merchantB.getId(), endpointA.getId(), "payment_intent.created", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private IdempotencyRecord idempotencyRecord(UUID merchantId, String key, String requestHash) {
        return new IdempotencyRecord(merchantId, key, "test.operation", requestHash,
                Instant.now().plus(1, ChronoUnit.DAYS));
    }
}
