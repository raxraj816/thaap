package com.thaap.payments.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.audit.AuditLog;
import com.thaap.payments.audit.AuditLogRepository;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.fraud.ConfirmDecision;
import com.thaap.payments.fraud.FeatureFreshnessStatus;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.fraud.FraudRiskLevel;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.ledger.LedgerEntry;
import com.thaap.payments.ledger.LedgerEntryRepository;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.payment.Refund;
import com.thaap.payments.payment.RefundRepository;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.review.FraudLabelRepository;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.risk.RiskOperatorService;
import com.thaap.payments.paymentmethod.CardFunding;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.thaap.payments.webhook.WebhookEndpointRepository;

@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ChargebackAndDisputeContractTests {
    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String RISK_KEY_HEADER = "X-Risk-Operator-Key";
    private static final String SCENARIO_TOKEN_HEADER = "X-Thaap-Scenario-Runner-Token";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private PaymentIntentRepository paymentIntentRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private FraudDecisionRepository fraudDecisionRepository;
    @Autowired private AuthorizationRepository authorizationRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DisputeOperationsService disputeOperationsService;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private RiskOperatorService riskOperatorService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private LedgerEntryRepository ledgerEntryRepository;
    @MockitoSpyBean private AuditLogRepository auditLogRepository;
    @MockitoSpyBean private DisputeOffsetAllocationRepository allocationRepository;
    @MockitoSpyBean private FraudLabelRepository fraudLabelRepository;
    @MockitoSpyBean private WebhookEndpointRepository webhookEndpointRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void capturedFraudChargebackCreatesBalancedAllocationLabelEvidenceAndExactReplay() throws Exception {
        CapturedFixture fixture = captured("chargeback-fraud", 6400L);
        Instant receivedAt = Instant.now().minusSeconds(60);
        String request = chargebackRequest("network-event-1", fixture.paymentIntentId(), 6400L,
                "FRAUDULENT_STOLEN_CREDENTIAL", receivedAt.minusSeconds(3600), receivedAt,
                receivedAt.plusSeconds(86400));

        JsonNode first = postChargeback(fixture.apiKey(), "chargeback-key-1", request, 201);
        JsonNode replay = postChargeback(fixture.apiKey(), "chargeback-key-1", request, 201);

        assertThat(replay).isEqualTo(first);
        assertThat(first.get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(first.get("allocatedAmount").asLong()).isEqualTo(6400L);
        assertThat(first.get("sourceAttestation").asText()).isEqualTo("SIMULATOR_MERCHANT_INGESTED");
        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.paymentIntentId()))
                .isEqualTo("DISPUTED");
        assertThat(count("chargebacks")).isEqualTo(1);
        assertThat(count("dispute_offset_allocations")).isEqualTo(1);
        assertThat(countWhere("ledger_entries", "source_type = 'CHARGEBACK'")).isEqualTo(2);
        assertThat(sum("ledger_entries", "direction = 'DEBIT' AND source_type = 'CHARGEBACK'"))
                .isEqualTo(6400L);
        assertThat(sum("ledger_entries", "direction = 'CREDIT' AND source_type = 'CHARGEBACK'"))
                .isEqualTo(6400L);
        assertThat(countWhere("fraud_labels", "source = 'CHARGEBACK'")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT taxonomy_version FROM fraud_labels
                 WHERE source = 'CHARGEBACK' AND payment_intent_id = ?
                """, String.class, fixture.paymentIntentId())).isEqualTo("fraud_taxonomy_v1");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_events
                WHERE stream_id = ? AND event_type = 'chargeback.created'
                """, Integer.class, fixture.paymentIntentId())).isEqualTo(1);
        byte[] chargebackPayload = jdbcTemplate.queryForObject("""
                SELECT payload_bytes FROM domain_events
                WHERE stream_id = ? AND event_type = 'chargeback.created'
                """, byte[].class, fixture.paymentIntentId());
        assertThat(new String(chargebackPayload, StandardCharsets.UTF_8)).isEqualTo(
                "{\"amountMinor\":6400,\"currency\":\"USD\","
                        + "\"reasonCode\":\"FRAUDULENT_STOLEN_CREDENTIAL\"}");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO fraud_labels (
                    id, merchant_id, payment_intent_id, payment_attempt_id, fraud_decision_id,
                    traffic_origin, source, label_value, fraud_type, evidence_occurred_at,
                    available_at, source_review_resolution_id, actor_operator_id,
                    supersedes_label_id, reason_code, created_at, source_chargeback_id,
                    source_post_authorization_signal_id, taxonomy_version)
                SELECT ?, merchant_id, payment_intent_id, payment_attempt_id, fraud_decision_id,
                    traffic_origin, source, label_value, fraud_type, evidence_occurred_at,
                    available_at, source_review_resolution_id, actor_operator_id,
                    supersedes_label_id, reason_code, created_at, source_chargeback_id,
                    source_post_authorization_signal_id, NULL
                  FROM fraud_labels WHERE payment_intent_id = ?
                """, UUID.randomUUID(), fixture.paymentIntentId()))
                .hasMessageContaining("New fraud labels require the active taxonomy version");

        UUID chargebackId = UUID.fromString(first.get("id").asText());
        JsonNode packet = objectMapper.readTree(mockMvc.perform(post("/chargebacks/{id}/evidence_packets",
                                chargebackId)
                        .header(API_KEY_HEADER, fixture.apiKey())
                        .header(IDEMPOTENCY_KEY, "evidence-key-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"deliveryConfirmed":true,"customerCommunicationPresent":false,
                                 "deviceContinuity":true}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(packet.get("status").asText()).isEqualTo("DRAFT");
        assertThat(packet.get("avsResult").asText()).isEqualTo("MATCH");
        assertThat(packet.get("cvcResult").asText()).isEqualTo("MATCH");
        assertThat(packet.get("sourceAttestation").asText()).isEqualTo("SIMULATOR_MERCHANT_INGESTED");
        assertThat(count("dispute_evidence_packets")).isEqualTo(1);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO dispute_evidence_packets (
                    id, merchant_id, chargeback_id, payment_intent_id, payment_attempt_id,
                    authorization_id, fraud_decision_id, status, source_attestation,
                    avs_result, cvc_result, three_ds_authenticated, delivery_confirmed,
                    customer_communication_present, refund_history_present, device_continuity,
                    due_at, created_at
                )
                SELECT ?, merchant_id, chargeback_id, payment_intent_id, payment_attempt_id,
                    authorization_id, fraud_decision_id, status, source_attestation,
                    'NO_MATCH', cvc_result, three_ds_authenticated, delivery_confirmed,
                    customer_communication_present, refund_history_present, device_continuity,
                    due_at, created_at
                FROM dispute_evidence_packets WHERE chargeback_id = ?
                """, UUID.randomUUID(), chargebackId))
                .hasMessageContaining("dispute evidence lineage mismatch");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO dispute_offset_allocations (
                    id, merchant_id, chargeback_id, payment_intent_id, refund_id, captured_amount,
                    prior_refund_amount, prior_dispute_amount, allocated_amount, currency, result, created_at
                )
                SELECT ?, merchant_id, chargeback_id, payment_intent_id, refund_id, captured_amount,
                    prior_refund_amount, prior_dispute_amount, allocated_amount - 1, currency, result, created_at
                FROM dispute_offset_allocations WHERE chargeback_id = ?
                """, UUID.randomUUID(), chargebackId))
                .hasMessageContaining("dispute allocation lineage mismatch");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO chargebacks (
                    id, merchant_id, external_event_key, request_fingerprint, payment_intent_id,
                    payment_attempt_id, authorization_id, fraud_decision_id, amount, allocated_amount,
                    currency, reason_code, status, source_attestation, traffic_origin,
                    evidence_occurred_at, received_at, available_at, evidence_due_at, created_at
                )
                SELECT ?, merchant_id, external_event_key || '.forged', request_fingerprint,
                    payment_intent_id, payment_attempt_id, authorization_id, fraud_decision_id,
                    amount, allocated_amount, currency, reason_code, status, source_attestation,
                    traffic_origin, evidence_occurred_at, received_at, available_at,
                    evidence_due_at, created_at
                FROM chargebacks WHERE id = ?
                """, UUID.randomUUID(), chargebackId))
                .hasMessageContaining("chargeback payment lineage mismatch");
    }

    @Test
    void refundFirstNonFraudChargebackRecordsZeroOffsetWithoutDebitOrLabel() throws Exception {
        CapturedFixture fixture = captured("chargeback-refunded", 7200L);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Refund refund = refundRepository.saveAndFlush(new Refund(fixture.merchantId(), fixture.paymentIntentId(),
                    fixture.paymentAttemptId(), fixture.authorizationId(), 7200L, "USD"));
            ledgerService.recordRefund(fixture.merchantId(), fixture.paymentIntentId(), refund.getId(), 7200L, "USD");
            PaymentIntent paymentIntent = paymentIntentRepository.findById(fixture.paymentIntentId()).orElseThrow();
            paymentIntent.refund();
        });
        Instant receivedAt = Instant.now().minusSeconds(60);
        JsonNode chargeback = postChargeback(fixture.apiKey(), "chargeback-refunded-key",
                chargebackRequest("network-event-refunded", fixture.paymentIntentId(), 7200L,
                        "PRODUCT_NOT_RECEIVED", receivedAt.minusSeconds(3600), receivedAt,
                        receivedAt.plusSeconds(86400)), 201);

        assertThat(chargeback.get("status").asText()).isEqualTo("OFFSET_BY_REFUND");
        assertThat(chargeback.get("allocatedAmount").asLong()).isZero();
        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.paymentIntentId()))
                .isEqualTo("REFUNDED");
        assertThat(countWhere("ledger_entries", "source_type = 'CHARGEBACK'")).isZero();
        assertThat(countWhere("fraud_labels", "source = 'CHARGEBACK'")).isZero();
    }

    @Test
    void duplicateExternalEventConflictingBodyAndForeignReadAreRejected() throws Exception {
        CapturedFixture fixture = captured("chargeback-dedup", 5000L);
        CapturedFixture foreign = captured("chargeback-foreign", 5100L);
        Instant receivedAt = Instant.now().minusSeconds(60);
        String firstRequest = chargebackRequest("same-event", fixture.paymentIntentId(), 1000L, "GENERAL",
                receivedAt.minusSeconds(60), receivedAt, receivedAt.plusSeconds(600));
        JsonNode first = postChargeback(fixture.apiKey(), "dedup-key-1", firstRequest, 201);

        mockMvc.perform(post("/chargebacks")
                        .header(API_KEY_HEADER, fixture.apiKey())
                        .header(IDEMPOTENCY_KEY, "dedup-key-2")
                        .contentType(APPLICATION_JSON)
                        .content(chargebackRequest("same-event", fixture.paymentIntentId(), 1001L, "GENERAL",
                                receivedAt.minusSeconds(60), receivedAt, receivedAt.plusSeconds(600))))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/chargebacks/{id}", UUID.fromString(first.get("id").asText()))
                        .header(API_KEY_HEADER, foreign.apiKey()))
                .andExpect(status().isNotFound());
        assertThat(count("chargebacks")).isEqualTo(1);
    }

    @Test
    void everyReasonHasExactFraudLabelSemantics() throws Exception {
        Map<String, String> fraudReasons = Map.of(
                "FRAUDULENT_STOLEN_CREDENTIAL", "STOLEN_PAYMENT_CREDENTIAL",
                "FRAUDULENT_ACCOUNT_TAKEOVER", "ACCOUNT_TAKEOVER",
                "FRAUDULENT_CARD_TESTING", "CARD_TESTING",
                "FRAUDULENT_FIRST_PARTY_MISUSE", "FIRST_PARTY_MISUSE");
        List<String> nonFraudReasons = List.of(
                "PRODUCT_NOT_RECEIVED", "DUPLICATE", "CREDIT_NOT_PROCESSED", "GENERAL");
        Instant receivedAt = Instant.now().minusSeconds(60);

        for (Map.Entry<String, String> reason : fraudReasons.entrySet()) {
            CapturedFixture fixture = captured("reason-" + reason.getKey().toLowerCase(), 2300L);
            postChargeback(fixture.apiKey(), "idem-" + reason.getKey(), chargebackRequest(
                    "event-" + reason.getKey(), fixture.paymentIntentId(), 1L, reason.getKey(),
                    receivedAt.minusSeconds(1), receivedAt, receivedAt.plusSeconds(600)), 201);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT fraud_type FROM fraud_labels
                    WHERE source_chargeback_id = (SELECT id FROM chargebacks WHERE payment_intent_id = ?)
                    """, String.class, fixture.paymentIntentId())).isEqualTo(reason.getValue());
        }
        for (String reason : nonFraudReasons) {
            CapturedFixture fixture = captured("reason-" + reason.toLowerCase(), 2400L);
            postChargeback(fixture.apiKey(), "idem-" + reason, chargebackRequest(
                    "event-" + reason, fixture.paymentIntentId(), 1L, reason,
                    receivedAt.minusSeconds(1), receivedAt, receivedAt.plusSeconds(600)), 201);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fraud_labels label
                    JOIN chargebacks chargeback ON chargeback.id = label.source_chargeback_id
                    WHERE chargeback.payment_intent_id = ?
                    """, Integer.class, fixture.paymentIntentId())).isZero();
        }
    }

    @Test
    void strictGrammarAmountStateCurrencyAndIndependentExternalDedupAreEnforced() throws Exception {
        CapturedFixture fixture = captured("strict-grammar", 5000L);
        Instant receivedAt = Instant.now().minusSeconds(60);
        String valid = chargebackRequest("machine.event:1", fixture.paymentIntentId(), 1L, "GENERAL",
                receivedAt.minusSeconds(1), receivedAt, receivedAt.plusSeconds(600));
        JsonNode first = postChargeback(fixture.apiKey(), "strict-first", valid, 201);
        JsonNode deduplicated = postChargeback(fixture.apiKey(), "strict-second", valid, 200);
        assertThat(deduplicated).isEqualTo(first);

        CapturedFixture over = captured("strict-over", 5000L);
        postChargeback(over.apiKey(), "strict-over", chargebackRequest("machine.event:2",
                over.paymentIntentId(), 5001L, "GENERAL", receivedAt.minusSeconds(1), receivedAt,
                receivedAt.plusSeconds(600)), 409);
        CapturedFixture currency = captured("strict-currency", 5000L);
        postChargeback(currency.apiKey(), "strict-currency", chargebackRequest("machine.event:3",
                currency.paymentIntentId(), 1L, "GENERAL", receivedAt.minusSeconds(1), receivedAt,
                receivedAt.plusSeconds(600)).replace("\"USD\"", "\"EUR\""), 409);
        postChargeback(fixture.apiKey(), "strict-zero", valid.replace("\"amount\":1", "\"amount\":0"), 400);
        postChargeback(fixture.apiKey(), "strict-key", valid.replace("machine.event:1", "unsafe event"), 400);
        postChargeback(fixture.apiKey(), "strict-source", valid.replace("}",
                ",\"sourceAttestation\":\"NETWORK_VERIFIED\"}"), 400);
        postChargeback(fixture.apiKey(), "strict-free-text", valid.replace("}",
                ",\"notes\":\"raw card 4111111111111111\"}"), 400);
        postChargeback(fixture.apiKey(), "strict-first", valid.replace("\"amount\":1", "\"amount\":2"), 409);

        CapturedFixture manual = captured("manual-capture", 3500L, CaptureMethod.MANUAL);
        postChargeback(manual.apiKey(), "manual-chargeback", chargebackRequest("manual.event",
                manual.paymentIntentId(), 1L, "GENERAL", receivedAt.minusSeconds(1), receivedAt,
                receivedAt.plusSeconds(600)), 409);
        assertThat(countWhere("chargebacks", "payment_intent_id = '" + manual.paymentIntentId() + "'"))
                .isZero();
    }

    @Test
    void expiredEvidenceAndAppendOnlyMutationAreRejectedAndMetricsStayBounded() throws Exception {
        CapturedFixture fixture = captured("expired-evidence", 4100L);
        Instant receivedAt = Instant.now().minusSeconds(120);
        JsonNode chargeback = postChargeback(fixture.apiKey(), "expired-create", chargebackRequest(
                "expired.event", fixture.paymentIntentId(), 4100L, "GENERAL",
                receivedAt.minusSeconds(1), receivedAt, receivedAt.plusSeconds(1)), 201);
        UUID chargebackId = UUID.fromString(chargeback.get("id").asText());

        mockMvc.perform(post("/chargebacks/{id}/evidence_packets", chargebackId)
                        .header(API_KEY_HEADER, fixture.apiKey())
                        .header(IDEMPOTENCY_KEY, "expired-packet")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"deliveryConfirmed":true,"customerCommunicationPresent":false,
                                 "deviceContinuity":true}
                                """))
                .andExpect(status().isConflict());
        assertThat(count("dispute_evidence_packets")).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE chargebacks SET amount = amount + 1 WHERE id = ?", chargebackId))
                .hasMessageContaining("Chargeback and dispute history is append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM chargebacks WHERE id = ?", chargebackId))
                .hasMessageContaining("Chargeback and dispute history is append-only");
        assertThat(meterRegistry.find("thaap.dispute.chargeback.ingestions").meters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactlyInAnyOrder("allocation", "reason_class", "result", "transaction"));
        assertThat(meterRegistry.find("thaap.dispute.evidence.packets")
                .tag("result", "DEADLINE_EXPIRED").tag("transaction", "rolled_back").counter())
                .isNotNull();
    }

    @Test
    void ledgerFailureRollsBackChargebackAllocationLabelAuditWebhookAndIdempotency() throws Exception {
        CapturedFixture fixture = captured("rollback-ledger", 4700L);
        doThrow(new IllegalStateException("injected chargeback ledger failure"))
                .when(ledgerEntryRepository).saveAll(org.mockito.ArgumentMatchers.<LedgerEntry>anyList());

        Instant receivedAt = Instant.now().minusSeconds(60);
        postChargeback(fixture.apiKey(), "rollback-ledger-key", chargebackRequest("rollback.ledger",
                fixture.paymentIntentId(), 4700L, "FRAUDULENT_CARD_TESTING",
                receivedAt.minusSeconds(1), receivedAt, receivedAt.plusSeconds(600)), 500);

        assertChargebackRollback(fixture, "rollback-ledger-key");
        assertThat(meterRegistry.find("thaap.dispute.chargeback.ingestions")
                .tag("transaction", "rolled_back").meters()).isEmpty();
    }

    @Test
    void auditFailureRollsBackEveryChargebackSideEffectAndReportsRolledBackMetric() throws Exception {
        CapturedFixture fixture = captured("rollback-audit", 4800L);
        doThrow(new IllegalStateException("injected chargeback audit failure"))
                .when(auditLogRepository).save(any(AuditLog.class));

        Instant receivedAt = Instant.now().minusSeconds(60);
        postChargeback(fixture.apiKey(), "rollback-audit-key", chargebackRequest("rollback.audit",
                fixture.paymentIntentId(), 4800L, "FRAUDULENT_ACCOUNT_TAKEOVER",
                receivedAt.minusSeconds(1), receivedAt, receivedAt.plusSeconds(600)), 500);

        assertChargebackRollback(fixture, "rollback-audit-key");
        assertThat(meterRegistry.find("thaap.dispute.chargeback.ingestions")
                .tag("transaction", "rolled_back").counter()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"allocation", "label", "webhook"})
    void injectedIntermediateFailureRollsBackAllEarlierAndLaterChargebackTruth(String boundary) throws Exception {
        CapturedFixture fixture = captured("rollback-" + boundary, 4900L);
        switch (boundary) {
            case "allocation" -> doThrow(new IllegalStateException("injected allocation failure"))
                    .when(allocationRepository).saveAndFlush(any(DisputeOffsetAllocation.class));
            case "label" -> doThrow(new IllegalStateException("injected label failure"))
                    .when(fraudLabelRepository).saveAndFlush(any(com.thaap.payments.review.FraudLabel.class));
            case "webhook" -> doThrow(new IllegalStateException("injected webhook failure"))
                    .when(webhookEndpointRepository).findActiveSubscribedEndpointIds(
                            any(UUID.class), anyString());
            default -> throw new IllegalArgumentException("Unknown rollback boundary " + boundary);
        }
        Instant receivedAt = Instant.now().minusSeconds(60);
        postChargeback(fixture.apiKey(), "rollback-" + boundary + "-key",
                chargebackRequest("rollback." + boundary, fixture.paymentIntentId(), 4900L,
                        "FRAUDULENT_STOLEN_CREDENTIAL", receivedAt.minusSeconds(1), receivedAt,
                        receivedAt.plusSeconds(600)), 500);

        assertChargebackRollback(fixture, "rollback-" + boundary + "-key");
    }

    @Test
    void forcedRefundFirstMakesChargebackWaitThenCommitZeroOffsetWithoutDoubleDebit() throws Exception {
        CapturedFixture fixture = captured("race-refund-first", 5200L);
        CountDownLatch refundOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseRefundCommit = new CountDownLatch(1);
        Instant receivedAt = Instant.now().minusSeconds(60);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> refund = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    try {
                        assertThat(performRefund(fixture, "race-refund-first-key", 5200L)
                                .getResponse().getStatus()).isEqualTo(201);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    refundOwnsPaymentLock.countDown();
                    await(releaseRefundCommit);
                });
                return null;
            });
            assertThat(refundOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict(fixture.paymentIntentId());
            Future<JsonNode> chargeback = executor.submit(() -> postChargeback(fixture.apiKey(),
                    "race-refund-first-chargeback", chargebackRequest("race.refund.first",
                            fixture.paymentIntentId(), 5200L, "FRAUDULENT_FIRST_PARTY_MISUSE",
                            receivedAt.minusSeconds(1), receivedAt, receivedAt.plusSeconds(600)), 201));
            awaitDatabaseLockWait("payment_intents");
            releaseRefundCommit.countDown();
            refund.get(5, TimeUnit.SECONDS);
            assertThat(chargeback.get(5, TimeUnit.SECONDS).get("status").asText())
                    .isEqualTo("OFFSET_BY_REFUND");
        }

        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.paymentIntentId()))
                .isEqualTo("REFUNDED");
        assertThat(count("refunds")).isOne();
        assertThat(count("chargebacks")).isOne();
        assertThat(countWhere("dispute_offset_allocations", "result = 'OFFSET_BY_REFUND'"
                + " AND allocated_amount = 0 AND prior_refund_amount = 5200")).isOne();
        assertThat(countWhere("ledger_entries", "source_type = 'REFUND'")).isEqualTo(2);
        assertThat(countWhere("ledger_entries", "source_type = 'CHARGEBACK'")).isZero();
        assertThat(countWhere("fraud_labels", "source = 'CHARGEBACK'"
                + " AND fraud_type = 'FIRST_PARTY_MISUSE'")).isOne();
    }

    @Test
    void forcedChargebackFirstMakesRefundWaitThenRejectWithoutDoubleCredit() throws Exception {
        CapturedFixture fixture = captured("race-chargeback-first", 5300L);
        CountDownLatch chargebackOwnsPaymentLock = new CountDownLatch(1);
        CountDownLatch releaseChargebackCommit = new CountDownLatch(1);
        Instant receivedAt = Instant.now().minusSeconds(60);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> chargeback = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    try {
                        postChargeback(fixture.apiKey(), "race-chargeback-first-key",
                                chargebackRequest("race.chargeback.first", fixture.paymentIntentId(), 5300L,
                                        "GENERAL", receivedAt.minusSeconds(1), receivedAt,
                                        receivedAt.plusSeconds(600)), 201);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                    chargebackOwnsPaymentLock.countDown();
                    await(releaseChargebackCommit);
                });
                return null;
            });
            assertThat(chargebackOwnsPaymentLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRowLockConflict(fixture.paymentIntentId());
            Future<MvcResult> refund = executor.submit(() ->
                    performRefund(fixture, "race-chargeback-first-refund", 5300L));
            awaitDatabaseLockWait("payment_intents");
            releaseChargebackCommit.countDown();
            chargeback.get(5, TimeUnit.SECONDS);
            assertThat(refund.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }

        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.paymentIntentId()))
                .isEqualTo("DISPUTED");
        assertThat(count("refunds")).isZero();
        assertThat(countWhere("chargebacks", "status = 'ALLOCATED' AND allocated_amount = 5300")).isOne();
        assertThat(countWhere("ledger_entries", "source_type = 'CHARGEBACK'")).isEqualTo(2);
        assertThat(countWhere("ledger_entries", "source_type = 'REFUND'")).isZero();
    }

    @Test
    void forcedConcurrentIdenticalExternalEventsWaitThenConvergeToOneFinancialTruth() throws Exception {
        CapturedFixture fixture = captured("race-external-dedup", 5400L);
        Instant receivedAt = Instant.now().minusSeconds(60);
        String body = chargebackRequest("race.external.same", fixture.paymentIntentId(), 5400L,
                "FRAUDULENT_CARD_TESTING", receivedAt.minusSeconds(1), receivedAt,
                receivedAt.plusSeconds(600));
        CountDownLatch ownerHasFinancialTruth = new CountDownLatch(1);
        CountDownLatch releaseOwnerCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> owner = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    transactionStatus -> {
                        try {
                            MvcResult result = performChargeback(
                                    fixture.apiKey(), "race-external-owner", body).andReturn();
                            assertThat(result.getResponse().getStatus()).isEqualTo(201);
                            ownerHasFinancialTruth.countDown();
                            await(releaseOwnerCommit);
                            return result;
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }));
            assertThat(ownerHasFinancialTruth.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> duplicate = executor.submit(() ->
                    performChargeback(fixture.apiKey(), "race-external-duplicate", body).andReturn());
            awaitDatabaseLockWait("merchants");
            releaseOwnerCommit.countDown();
            MvcResult original = owner.get(5, TimeUnit.SECONDS);
            MvcResult replay = duplicate.get(5, TimeUnit.SECONDS);
            assertThat(replay.getResponse().getStatus()).isEqualTo(200);
            assertThat(replay.getResponse().getContentAsString())
                    .isEqualTo(original.getResponse().getContentAsString());
        }

        assertThat(count("chargebacks")).isOne();
        assertThat(count("dispute_offset_allocations")).isOne();
        assertThat(countWhere("ledger_entries", "source_type = 'CHARGEBACK'")).isEqualTo(2);
        assertThat(countWhere("fraud_labels", "source = 'CHARGEBACK'")).isOne();
        assertThat(countWhere("audit_logs", "action = 'chargeback.created'")).isOne();
    }

    @Test
    void disputeWorkflowUsesGovernedDeadlineImmutableVersionsDualControlAndTrustedOutcome() throws Exception {
        CapturedFixture fixture = captured("dispute-operations", 6100L);
        OperatorAccess viewer = createOperator(fixture, "dispute-viewer", "VIEWER");
        OperatorAccess analyst = createOperator(fixture, "dispute-analyst", "ANALYST");
        OperatorAccess manager = createOperator(fixture, "dispute-manager", "RISK_MANAGER");
        Instant receivedAt = Instant.now().minusSeconds(60);
        Instant callerClaimedDueAt = receivedAt.plusSeconds(86400);
        JsonNode chargeback = postChargeback(fixture.apiKey(), "dispute-operations-create",
                chargebackRequest("dispute.operations.created", fixture.paymentIntentId(), 6100L,
                        "FRAUDULENT_STOLEN_CREDENTIAL", receivedAt.minusSeconds(3600), receivedAt,
                        callerClaimedDueAt), 201);
        UUID chargebackId = UUID.fromString(chargeback.get("id").asText());

        JsonNode initial = json(mockMvc.perform(get("/risk/disputes/{id}", chargebackId)
                .header(RISK_KEY_HEADER, viewer.secret())), 200);
        assertThat(initial.get("lifecycleStatus").asText()).isEqualTo("DRAFT");
        assertThat(initial.get("readinessStatus").asText()).isEqualTo("INCOMPLETE");
        JsonNode viewerQueue = json(mockMvc.perform(get("/risk/disputes")
                .header(RISK_KEY_HEADER, viewer.secret())), 200);
        assertThat(viewerQueue).hasSize(1);
        assertThat(viewerQueue.get(0).get("chargebackId").asText()).isEqualTo(chargebackId.toString());
        assertThat(Instant.parse(initial.get("dueAt").asText()))
                .isEqualTo(receivedAt.plus(7, java.time.temporal.ChronoUnit.DAYS)
                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(Instant.parse(initial.get("dueAt").asText())).isNotEqualTo(callerClaimedDueAt);
        int ledgerRows = count("ledger_entries");
        int fraudLabels = count("fraud_labels");

        String incompleteEvidence = "{\"deliveryConfirmed\":false,"
                + "\"customerCommunicationPresent\":false,\"deviceContinuity\":false}";
        mockMvc.perform(post("/risk/disputes/{id}/evidence-versions", chargebackId)
                        .header(RISK_KEY_HEADER, viewer.secret()).header(IDEMPOTENCY_KEY, "viewer-evidence")
                        .contentType(APPLICATION_JSON).content(incompleteEvidence))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/risk/disputes/{id}/evidence-versions", chargebackId)
                        .header(RISK_KEY_HEADER, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-evidence")
                        .contentType(APPLICATION_JSON).content(incompleteEvidence))
                .andExpect(status().isForbidden());
        JsonNode incomplete = json(mockMvc.perform(post("/risk/disputes/{id}/evidence-versions", chargebackId)
                .header(RISK_KEY_HEADER, manager.secret()).header(IDEMPOTENCY_KEY, "dispute-evidence-v1")
                .contentType(APPLICATION_JSON)
                .content(incompleteEvidence)), 200);
        assertThat(incomplete.get("latestEvidenceVersion").asInt()).isEqualTo(1);
        assertThat(incomplete.get("missingRequirements").toString())
                .contains("AUTHENTICATION_OR_DEVICE_CONTINUITY");

        JsonNode ready = json(mockMvc.perform(post("/risk/disputes/{id}/evidence-versions", chargebackId)
                .header(RISK_KEY_HEADER, manager.secret()).header(IDEMPOTENCY_KEY, "dispute-evidence-v2")
                .contentType(APPLICATION_JSON)
                .content("{\"deliveryConfirmed\":false,\"customerCommunicationPresent\":false,"
                        + "\"deviceContinuity\":true}")), 200);
        assertThat(ready.get("lifecycleStatus").asText()).isEqualTo("READY");
        assertThat(ready.get("latestEvidenceVersion").asInt()).isEqualTo(2);

        mockMvc.perform(post("/risk/disputes/{id}/submit", chargebackId)
                        .header(RISK_KEY_HEADER, analyst.secret()).header(IDEMPOTENCY_KEY, "analyst-submit")
                        .contentType(APPLICATION_JSON).content("{\"expectedEvidenceVersion\":2}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/risk/disputes/{id}/submit", chargebackId)
                        .header(RISK_KEY_HEADER, viewer.secret()).header(IDEMPOTENCY_KEY, "viewer-submit")
                        .contentType(APPLICATION_JSON).content("{\"expectedEvidenceVersion\":2}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/risk/disputes/{id}/submit", chargebackId)
                        .header(RISK_KEY_HEADER, manager.secret()).header(IDEMPOTENCY_KEY, "stale-submit")
                        .contentType(APPLICATION_JSON).content("{\"expectedEvidenceVersion\":1}"))
                .andExpect(status().isConflict());
        JsonNode submitted = json(mockMvc.perform(post("/risk/disputes/{id}/submit", chargebackId)
                .header(RISK_KEY_HEADER, manager.secret()).header(IDEMPOTENCY_KEY, "manager-submit")
                .contentType(APPLICATION_JSON).content("{\"expectedEvidenceVersion\":2}")), 200);
        assertThat(submitted.get("lifecycleStatus").asText()).isEqualTo("SUBMITTED");

        Instant outcomeAt = Instant.now();
        String outcomeBody = "{\"externalEventKey\":\"dispute.outcome.1\",\"outcome\":\"WON\","
                + "\"occurredAt\":\"" + outcomeAt + "\"}";
        mockMvc.perform(post("/simulator/disputes/{id}/outcomes", chargebackId)
                        .header(API_KEY_HEADER, fixture.apiKey()).header(IDEMPOTENCY_KEY, "untrusted-outcome")
                        .contentType(APPLICATION_JSON).content(outcomeBody))
                .andExpect(status().isForbidden());
        JsonNode outcome = json(mockMvc.perform(post("/simulator/disputes/{id}/outcomes", chargebackId)
                .header(API_KEY_HEADER, fixture.apiKey())
                .header(SCENARIO_TOKEN_HEADER, "test-scenario-runner-token")
                .header(IDEMPOTENCY_KEY, "trusted-outcome")
                .contentType(APPLICATION_JSON).content(outcomeBody)), 200);
        JsonNode replay = json(mockMvc.perform(post("/simulator/disputes/{id}/outcomes", chargebackId)
                .header(API_KEY_HEADER, fixture.apiKey())
                .header(SCENARIO_TOKEN_HEADER, "test-scenario-runner-token")
                .header(IDEMPOTENCY_KEY, "trusted-outcome")
                .contentType(APPLICATION_JSON).content(outcomeBody)), 200);
        assertThat(replay).isEqualTo(outcome);
        assertThat(outcome.get("outcome").asText()).isEqualTo("WON");

        assertThat(count("dispute_evidence_versions")).isEqualTo(2);
        assertThat(countWhere("dispute_workflow_events", "event_type = 'EVIDENCE_REVISED'")).isEqualTo(2);
        assertThat(countWhere("dispute_workflow_events", "event_type = 'SUBMITTED'")).isOne();
        assertThat(countWhere("dispute_workflow_events", "event_type = 'OUTCOME_WON'")).isOne();
        assertThat(countWhere("audit_logs", "action = 'dispute.evidence_revised'")).isEqualTo(2);
        assertThat(countWhere("audit_logs", "action = 'dispute.submitted'")).isOne();
        assertThat(countWhere("audit_logs", "action = 'dispute.outcome_recorded'")).isOne();
        assertThat(count("ledger_entries")).isEqualTo(ledgerRows);
        assertThat(count("fraud_labels")).isEqualTo(fraudLabels);
        assertThat(value("SELECT lifecycle_status FROM dispute_workflows WHERE chargeback_id = ?", chargebackId))
                .isEqualTo("WON");

        UUID workflowId = UUID.fromString(initial.get("id").asText());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE dispute_evidence_versions SET device_continuity = FALSE WHERE dispute_workflow_id = ?",
                workflowId)).hasMessageContaining("Dispute operations history is append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM dispute_workflow_events WHERE dispute_workflow_id = ?", workflowId))
                .hasMessageContaining("Dispute operations history is append-only");
    }

    @Test
    void forcedConcurrentSubmissionsSerializeToOneSubmittedVersionAndEvent() throws Exception {
        CapturedFixture fixture = captured("dispute-submit-race", 6200L);
        OperatorAccess manager = createOperator(fixture, "submit-race-manager", "RISK_MANAGER");
        Instant receivedAt = Instant.now().minusSeconds(60);
        JsonNode chargeback = postChargeback(fixture.apiKey(), "submit-race-chargeback",
                chargebackRequest("dispute.submit.race", fixture.paymentIntentId(), 6200L,
                        "FRAUDULENT_STOLEN_CREDENTIAL", receivedAt.minusSeconds(60), receivedAt,
                        receivedAt.plusSeconds(86400)), 201);
        UUID chargebackId = UUID.fromString(chargeback.get("id").asText());
        json(mockMvc.perform(post("/risk/disputes/{id}/evidence-versions", chargebackId)
                .header(RISK_KEY_HEADER, manager.secret()).header(IDEMPOTENCY_KEY, "submit-race-evidence")
                .contentType(APPLICATION_JSON)
                .content("{\"deliveryConfirmed\":false,\"customerCommunicationPresent\":false,"
                        + "\"deviceContinuity\":true}")), 200);
        CountDownLatch ownerPreparedSubmission = new CountDownLatch(1);
        CountDownLatch releaseOwnerCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> owner = executor.submit(() -> new TransactionTemplate(transactionManager).execute(
                    transactionStatus -> {
                        try {
                            MvcResult result = mockMvc.perform(post("/risk/disputes/{id}/submit", chargebackId)
                                    .header(RISK_KEY_HEADER, manager.secret())
                                    .header(IDEMPOTENCY_KEY, "submit-race-owner")
                                    .contentType(APPLICATION_JSON)
                                    .content("{\"expectedEvidenceVersion\":1}"))
                                    .andReturn();
                            assertThat(result.getResponse().getStatus()).isEqualTo(200);
                            ownerPreparedSubmission.countDown();
                            await(releaseOwnerCommit);
                            return result;
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }));
            assertThat(ownerPreparedSubmission.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRiskOperatorLockConflict(manager.id());
            Future<MvcResult> contender = executor.submit(() -> mockMvc.perform(
                    post("/risk/disputes/{id}/submit", chargebackId)
                            .header(RISK_KEY_HEADER, manager.secret())
                            .header(IDEMPOTENCY_KEY, "submit-race-contender")
                            .contentType(APPLICATION_JSON)
                            .content("{\"expectedEvidenceVersion\":1}"))
                    .andReturn());
            awaitDatabaseLockWait("risk_operators");
            releaseOwnerCommit.countDown();
            assertThat(owner.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(contender.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }

        assertThat(value("SELECT lifecycle_status FROM dispute_workflows WHERE chargeback_id = ?", chargebackId))
                .isEqualTo("SUBMITTED");
        assertThat(countWhere("dispute_workflow_events", "event_type = 'SUBMITTED'")).isOne();
        assertThat(countWhere("audit_logs", "action = 'dispute.submitted'")).isOne();
    }

    @Test
    void forcedOperatorDisableBeforeEvidenceRevisionRejectsInFlightAndFutureMutations()
            throws Exception {
        CapturedFixture fixture = captured("dispute-operator-disable", 6300L);
        Merchant merchant = merchantRepository.findById(fixture.merchantId()).orElseThrow();
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), merchant.getName(), merchant.getApiKeyPrefix());
        RiskOperatorService.CreationResult manager = riskOperatorService.create(
                merchantContext, "dispute-operator-disable-manager",
                RiskOperatorRole.RISK_MANAGER, "dispute-operator-disable-manager");
        RiskOperatorContext managerContext = new RiskOperatorContext(
                merchant.getId(), manager.operator().getId(), manager.operator().getOperatorCode(),
                manager.operator().getRole(), manager.credentialPrefix());
        Instant receivedAt = Instant.now().minusSeconds(60);
        JsonNode chargeback = postChargeback(fixture.apiKey(), "dispute-operator-disable-create",
                chargebackRequest("dispute.operator.disable", fixture.paymentIntentId(), 6300L,
                        "FRAUDULENT_STOLEN_CREDENTIAL", receivedAt.minusSeconds(60), receivedAt,
                        receivedAt.plusSeconds(86400)), 201);
        UUID chargebackId = UUID.fromString(chargeback.get("id").asText());
        CountDownLatch disableOwnsActorLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        CountDownLatch revisionStarted = new CountDownLatch(1);
        AtomicInteger revisionBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    riskOperatorService.disable(merchantContext, manager.operator().getId(),
                            "dispute-operator-disable-disable");
                    disableOwnsActorLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            assertThat(disableOwnsActorLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRiskOperatorLockConflict(manager.operator().getId());

            Future<?> revision = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                        revisionBackendPid.set(currentBackendPid());
                        revisionStarted.countDown();
                        disputeOperationsService.reviseEvidence(managerContext, chargebackId,
                                false, false, true, "dispute-operator-disable-in-flight");
                    }));
            assertThat(revisionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBackendLockWait(revisionBackendPid.get());

            releaseDisable.countDown();
            disable.get(5, TimeUnit.SECONDS);
            assertNotFound(revision);
        } finally {
            releaseDisable.countDown();
        }

        assertThat(value("SELECT status FROM risk_operators WHERE id = ?",
                manager.operator().getId())).isEqualTo("DISABLED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT disabled_at IS NOT NULL FROM risk_operator_credentials
                 WHERE merchant_id = ? AND risk_operator_id = ?
                """, Boolean.class, merchant.getId(), manager.operator().getId())).isTrue();
        assertThat(value("SELECT lifecycle_status FROM dispute_workflows WHERE chargeback_id = ?",
                chargebackId)).isEqualTo("DRAFT");
        assertThat(count("dispute_evidence_versions")).isZero();
        assertThat(countWhere("dispute_workflow_events", "event_type = 'EVIDENCE_REVISED'")).isZero();
        assertThat(countWhere("audit_logs", "action = 'dispute.evidence_revised'")).isZero();
        assertThatThrownBy(() -> disputeOperationsService.reviseEvidence(managerContext,
                chargebackId, false, false, true, "dispute-operator-disable-future"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        mockMvc.perform(post("/risk/disputes/{id}/evidence-versions", chargebackId)
                        .header(RISK_KEY_HEADER, manager.rawSecret())
                        .header(IDEMPOTENCY_KEY, "dispute-operator-disable-future-http")
                        .contentType(APPLICATION_JSON)
                        .content("{\"deliveryConfirmed\":false,"
                                + "\"customerCommunicationPresent\":false,"
                                + "\"deviceContinuity\":true}"))
                .andExpect(status().isUnauthorized());
        assertThat(count("dispute_evidence_versions")).isZero();
    }

    @Test
    void forcedOperatorDisableBeforeSubmissionRejectsInFlightAndFutureMutations()
            throws Exception {
        CapturedFixture fixture = captured("dispute-submit-operator-disable", 6350L);
        Merchant merchant = merchantRepository.findById(fixture.merchantId()).orElseThrow();
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), merchant.getName(), merchant.getApiKeyPrefix());
        RiskOperatorService.CreationResult manager = riskOperatorService.create(
                merchantContext, "dispute-submit-operator-disable-manager",
                RiskOperatorRole.RISK_MANAGER, "dispute-submit-operator-disable-manager");
        RiskOperatorContext managerContext = new RiskOperatorContext(
                merchant.getId(), manager.operator().getId(), manager.operator().getOperatorCode(),
                manager.operator().getRole(), manager.credentialPrefix());
        Instant receivedAt = Instant.now().minusSeconds(60);
        JsonNode chargeback = postChargeback(fixture.apiKey(), "dispute-submit-operator-disable-create",
                chargebackRequest("dispute.submit.operator.disable", fixture.paymentIntentId(), 6350L,
                        "FRAUDULENT_STOLEN_CREDENTIAL", receivedAt.minusSeconds(60), receivedAt,
                        receivedAt.plusSeconds(86400)), 201);
        UUID chargebackId = UUID.fromString(chargeback.get("id").asText());
        DisputeOperationsService.WorkflowView ready = disputeOperationsService.reviseEvidence(
                managerContext, chargebackId, false, false, true,
                "dispute-submit-operator-disable-evidence");
        assertThat(ready.lifecycleStatus()).isEqualTo(DisputeWorkflowStatus.READY);
        assertThat(ready.latestEvidenceVersion()).isEqualTo(1);
        CountDownLatch disableOwnsActorLock = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        CountDownLatch submissionStarted = new CountDownLatch(1);
        AtomicInteger submissionBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> disable = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                    riskOperatorService.disable(merchantContext, manager.operator().getId(),
                            "dispute-submit-operator-disable-disable");
                    disableOwnsActorLock.countDown();
                    await(releaseDisable);
                });
                return null;
            });
            assertThat(disableOwnsActorLock.await(5, TimeUnit.SECONDS)).isTrue();
            awaitRiskOperatorLockConflict(manager.operator().getId());

            Future<?> submission = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                        submissionBackendPid.set(currentBackendPid());
                        submissionStarted.countDown();
                        disputeOperationsService.submit(managerContext, chargebackId, 1,
                                "dispute-submit-operator-disable-in-flight");
                    }));
            assertThat(submissionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBackendLockWait(submissionBackendPid.get());

            releaseDisable.countDown();
            disable.get(5, TimeUnit.SECONDS);
            assertNotFound(submission);
        } finally {
            releaseDisable.countDown();
        }

        assertThat(value("SELECT status FROM risk_operators WHERE id = ?",
                manager.operator().getId())).isEqualTo("DISABLED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT disabled_at IS NOT NULL FROM risk_operator_credentials
                 WHERE merchant_id = ? AND risk_operator_id = ?
                """, Boolean.class, merchant.getId(), manager.operator().getId())).isTrue();
        assertThat(value("SELECT lifecycle_status FROM dispute_workflows WHERE chargeback_id = ?",
                chargebackId)).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT submitted_evidence_version_id IS NULL AND submitted_at IS NULL
                  FROM dispute_workflows WHERE chargeback_id = ?
                """, Boolean.class, chargebackId)).isTrue();
        assertThat(count("dispute_evidence_versions")).isOne();
        assertThat(countWhere("dispute_workflow_events", "event_type = 'EVIDENCE_REVISED'")).isOne();
        assertThat(countWhere("dispute_workflow_events", "event_type = 'SUBMITTED'")).isZero();
        assertThat(countWhere("audit_logs", "action = 'dispute.evidence_revised'")).isOne();
        assertThat(countWhere("audit_logs", "action = 'dispute.submitted'")).isZero();
        assertThatThrownBy(() -> disputeOperationsService.submit(managerContext,
                chargebackId, 1, "dispute-submit-operator-disable-future"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        mockMvc.perform(post("/risk/disputes/{id}/submit", chargebackId)
                        .header(RISK_KEY_HEADER, manager.rawSecret())
                        .header(IDEMPOTENCY_KEY, "dispute-submit-operator-disable-future-http")
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedEvidenceVersion\":1}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?
                """, Integer.class, "dispute-submit-operator-disable-future-http")).isZero();
        assertThat(countWhere("dispute_workflow_events", "event_type = 'SUBMITTED'")).isZero();
    }

    @Test
    void completedDisputeMutationReplaysReauthorizeLiveManagerAfterDisable()
            throws Exception {
        CapturedFixture fixture = captured("dispute-disabled-completed-replay", 6375L);
        Merchant merchant = merchantRepository.findById(fixture.merchantId()).orElseThrow();
        MerchantContext merchantContext = new MerchantContext(
                merchant.getId(), merchant.getName(), merchant.getApiKeyPrefix());
        RiskOperatorService.CreationResult manager = riskOperatorService.create(
                merchantContext, "dispute-disabled-completed-replay-manager",
                RiskOperatorRole.RISK_MANAGER, "dispute-disabled-completed-replay-manager");
        RiskOperatorContext managerContext = new RiskOperatorContext(
                merchant.getId(), manager.operator().getId(), manager.operator().getOperatorCode(),
                manager.operator().getRole(), manager.credentialPrefix());
        Instant receivedAt = Instant.now().minusSeconds(60);
        JsonNode chargeback = postChargeback(fixture.apiKey(), "dispute-disabled-completed-replay-create",
                chargebackRequest("dispute.disabled.completed.replay", fixture.paymentIntentId(), 6375L,
                        "FRAUDULENT_STOLEN_CREDENTIAL", receivedAt.minusSeconds(60), receivedAt,
                        receivedAt.plusSeconds(86400)), 201);
        UUID chargebackId = UUID.fromString(chargeback.get("id").asText());
        String evidenceKey = "dispute-disabled-completed-replay-evidence";
        String evidenceOperation = "risk.disputes.evidence.revise:" + chargebackId;
        DisputeOperationsController.EvidenceRevisionRequest evidenceRequest =
                new DisputeOperationsController.EvidenceRevisionRequest(false, false, true);
        ResponseEntity<DisputeOperationsService.WorkflowView> revised =
                idempotencyService.executeAuthorized(managerContext, evidenceKey,
                        evidenceOperation, evidenceRequest,
                        DisputeOperationsService.WorkflowView.class,
                        () -> disputeOperationsService.authorizeEvidenceRevisionReplay(
                                managerContext, chargebackId),
                        () -> ResponseEntity.ok(disputeOperationsService.reviseEvidence(
                                managerContext, chargebackId, false, false, true,
                                "dispute-disabled-completed-replay-evidence-original")));
        ResponseEntity<DisputeOperationsService.WorkflowView> activeEvidenceReplay =
                idempotencyService.executeAuthorized(managerContext, evidenceKey,
                        evidenceOperation, evidenceRequest,
                        DisputeOperationsService.WorkflowView.class,
                        () -> disputeOperationsService.authorizeEvidenceRevisionReplay(
                                managerContext, chargebackId),
                        () -> {
                            throw new AssertionError("Completed evidence replay invoked its operation");
                        });
        assertThat(activeEvidenceReplay.getBody()).isEqualTo(revised.getBody());

        String submitKey = "dispute-disabled-completed-replay-submit";
        String submitOperation = "risk.disputes.submit:" + chargebackId;
        DisputeOperationsController.SubmitRequest submitRequest =
                new DisputeOperationsController.SubmitRequest(1);
        ResponseEntity<DisputeOperationsService.WorkflowView> submitted =
                idempotencyService.executeAuthorized(managerContext, submitKey,
                        submitOperation, submitRequest,
                        DisputeOperationsService.WorkflowView.class,
                        () -> disputeOperationsService.authorizeSubmissionReplay(
                                managerContext, chargebackId),
                        () -> ResponseEntity.ok(disputeOperationsService.submit(
                                managerContext, chargebackId, 1,
                                "dispute-disabled-completed-replay-submit-original")));
        ResponseEntity<DisputeOperationsService.WorkflowView> activeSubmitReplay =
                idempotencyService.executeAuthorized(managerContext, submitKey,
                        submitOperation, submitRequest,
                        DisputeOperationsService.WorkflowView.class,
                        () -> disputeOperationsService.authorizeSubmissionReplay(
                                managerContext, chargebackId),
                        () -> {
                            throw new AssertionError("Completed submission replay invoked its operation");
                        });
        assertThat(activeSubmitReplay.getBody()).isEqualTo(submitted.getBody());

        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus ->
                riskOperatorService.disable(merchantContext, manager.operator().getId(),
                        "dispute-disabled-completed-replay-disable"));

        assertThatThrownBy(() -> idempotencyService.executeAuthorized(
                managerContext, evidenceKey, evidenceOperation, evidenceRequest,
                DisputeOperationsService.WorkflowView.class,
                () -> disputeOperationsService.authorizeEvidenceRevisionReplay(
                        managerContext, chargebackId),
                () -> {
                    throw new AssertionError("Disabled evidence replay invoked its operation");
                }))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> idempotencyService.executeAuthorized(
                managerContext, submitKey, submitOperation, submitRequest,
                DisputeOperationsService.WorkflowView.class,
                () -> disputeOperationsService.authorizeSubmissionReplay(
                        managerContext, chargebackId),
                () -> {
                    throw new AssertionError("Disabled submission replay invoked its operation");
                }))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));

        assertThat(value("SELECT status FROM risk_operators WHERE id = ?",
                manager.operator().getId())).isEqualTo("DISABLED");
        assertThat(value("SELECT lifecycle_status FROM dispute_workflows WHERE chargeback_id = ?",
                chargebackId)).isEqualTo("SUBMITTED");
        assertThat(count("dispute_evidence_versions")).isOne();
        assertThat(countWhere("dispute_workflow_events", "event_type = 'EVIDENCE_REVISED'")).isOne();
        assertThat(countWhere("dispute_workflow_events", "event_type = 'SUBMITTED'")).isOne();
        assertThat(countWhere("audit_logs", "action = 'dispute.evidence_revised'")).isOne();
        assertThat(countWhere("audit_logs", "action = 'dispute.submitted'")).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM idempotency_records
                 WHERE idempotency_key IN (?, ?) AND status = 'COMPLETED' AND http_status = 200
                """, Integer.class, evidenceKey, submitKey)).isEqualTo(2);
    }

    private CapturedFixture captured(String name, long amount) {
        return captured(name, amount, CaptureMethod.AUTOMATIC);
    }

    private CapturedFixture captured(String name, long amount, CaptureMethod captureMethod) {
        String rawApiKey = "sk_test_" + name + "_12345678901234567890";
        return new TransactionTemplate(transactionManager).execute(status -> {
            Merchant merchant = merchantRepository.save(new Merchant(name, rawApiKey));
            Customer customer = customerRepository.save(new Customer(merchant.getId(), "email-hash-" + name, null));
            PaymentMethod method = paymentMethodRepository.save(new PaymentMethod(merchant.getId(), customer.getId(),
                    "tok_" + name, "fingerprint-" + name, "499999", "0001", CardFunding.CREDIT, "US"));
            PaymentIntent intent = paymentIntentRepository.save(new PaymentIntent(merchant.getId(), customer.getId(),
                    amount, "USD", captureMethod));
            PaymentAttempt attempt = paymentAttemptRepository.save(new PaymentAttempt(merchant.getId(), intent.getId(),
                    method.getId(), "confirm-" + name, 1));
            Instant decisionTime = Instant.now().minusSeconds(120);
            FraudDecision decision = fraudDecisionRepository.save(new FraudDecision(merchant.getId(), attempt.getId(),
                    new ConfirmDecision(FraudDecisionAction.ALLOW, BigDecimal.ZERO, FraudRiskLevel.LOW,
                            "rules_v1", null, List.of(FraudDecisionReasonCode.NO_RULE_MATCH)),
                    1L, decisionTime, decisionTime.plusMillis(1), FeatureFreshnessStatus.FRESH, 0L));
            attempt.attachFraudDecision(decision.getId());
            attempt.markReadyForProcessor();
            Authorization authorization = authorizationRepository.save(new Authorization(merchant.getId(),
                    attempt.getId(), "SIMULATOR", "auth_" + name, "APPROVED", NetworkStatus.APPROVED,
                    amount, amount, "USD", AuthorizationStatus.APPROVED, VerificationResult.PASS,
                    VerificationResult.PASS, VerificationResult.PASS, ThreeDsResult.NOT_REQUIRED, "APPROVED"));
            attempt.markAuthorized(authorization.getProcessorReferenceId());
            intent.markProcessingAfterConfirm();
            intent.markRequiresCaptureAfterAuthorization();
            ledgerService.recordCapture(merchant.getId(), intent.getId(), amount, "USD");
            intent.capture();
            return new CapturedFixture(merchant.getId(), rawApiKey, intent.getId(), attempt.getId(),
                    authorization.getId());
        });
    }

    private JsonNode postChargeback(String apiKey, String idempotencyKey, String body, int statusCode)
            throws Exception {
        String response = performChargeback(apiKey, idempotencyKey, body)
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private OperatorAccess createOperator(CapturedFixture fixture, String key, String role) throws Exception {
        JsonNode response = json(mockMvc.perform(post("/risk/operators")
                .header(API_KEY_HEADER, fixture.apiKey()).header(IDEMPOTENCY_KEY, key)
                .contentType(APPLICATION_JSON).content("{\"role\":\"" + role + "\"}")), 201);
        return new OperatorAccess(UUID.fromString(response.get("id").asText()),
                response.get("credential").asText());
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions action, int statusCode)
            throws Exception {
        return objectMapper.readTree(action.andExpect(status().is(statusCode)).andReturn()
                .getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions performChargeback(
            String apiKey, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/chargebacks")
                .header(API_KEY_HEADER, apiKey)
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(APPLICATION_JSON).content(body));
    }

    private MvcResult performRefund(CapturedFixture fixture, String idempotencyKey, long amount) throws Exception {
        return mockMvc.perform(post("/refunds")
                        .header(API_KEY_HEADER, fixture.apiKey())
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"paymentIntentId":"%s","amount":%d}
                                """.formatted(fixture.paymentIntentId(), amount)))
                .andReturn();
    }

    private String chargebackRequest(String externalEventKey, UUID paymentIntentId, long amount,
            String reasonCode, Instant evidenceOccurredAt, Instant receivedAt, Instant dueAt) {
        return """
                {"externalEventKey":"%s","paymentIntentId":"%s","amount":%d,"currency":"USD",
                 "reasonCode":"%s","evidenceOccurredAt":"%s","receivedAt":"%s","evidenceDueAt":"%s"}
                """.formatted(externalEventKey, paymentIntentId, amount, reasonCode, evidenceOccurredAt,
                receivedAt, dueAt);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, String predicate) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
                Integer.class);
    }

    private long sum(String table, String predicate) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM " + table + " WHERE " + predicate,
                Long.class);
    }

    private String value(String sql, UUID id) {
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }

    private void assertChargebackRollback(CapturedFixture fixture, String idempotencyKey) {
        assertThat(value("SELECT status FROM payment_intents WHERE id = ?", fixture.paymentIntentId()))
                .isEqualTo("CAPTURED");
        assertThat(count("chargebacks")).isZero();
        assertThat(count("dispute_offset_allocations")).isZero();
        assertThat(countWhere("ledger_entries", "source_type = 'CHARGEBACK'")).isZero();
        assertThat(countWhere("fraud_labels", "source = 'CHARGEBACK'")).isZero();
        assertThat(countWhere("audit_logs", "action = 'chargeback.created'")).isZero();
        assertThat(countWhere("webhook_events", "event_type = 'chargeback.created' OR event_type = 'fraud_label.created'"))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class, idempotencyKey)).isZero();
    }

    private void awaitRowLockConflict(UUID rowId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT id FROM payment_intents WHERE id = ? FOR UPDATE NOWAIT")) {
                connection.setAutoCommit(false);
                statement.setObject(1, rowId);
                statement.executeQuery();
                connection.rollback();
            } catch (SQLException exception) {
                if ("55P03".equals(exception.getSQLState())) {
                    return;
                }
                throw exception;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected PaymentIntent row lock was not observed");
    }

    private void awaitRiskOperatorLockConflict(UUID operatorId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT id FROM risk_operators WHERE id = ? FOR UPDATE NOWAIT
                            """)) {
                connection.setAutoCommit(false);
                statement.setObject(1, operatorId);
                statement.executeQuery();
                connection.rollback();
            } catch (SQLException exception) {
                if ("55P03".equals(exception.getSQLState())) {
                    return;
                }
                throw exception;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected risk operator row lock was not observed");
    }

    private void awaitDatabaseLockWait(String tableName) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid()
                          AND wait_event_type = 'Lock'
                          AND query LIKE ?
                    )
                    """, Boolean.class, "%" + tableName + "%");
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Competing operation did not wait on " + tableName);
    }

    private int currentBackendPid() {
        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
        if (backendPid == null) {
            throw new IllegalStateException("PostgreSQL backend PID was unavailable");
        }
        return backendPid;
    }

    private void awaitBackendLockWait(int backendPid) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                         WHERE pid = ? AND wait_event_type = 'Lock'
                    )
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Dispute mutation did not wait on the risk operator lock");
    }

    private void assertNotFound(Future<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected disabled-actor operation to fail");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause.getCause() != null && !(cause instanceof ResponseStatusException)) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) cause).getStatusCode().value()).isEqualTo(404);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for Chargeback and dispute forced ordering latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chargeback and dispute forced ordering was interrupted", exception);
        }
    }

    private record CapturedFixture(UUID merchantId, String apiKey, UUID paymentIntentId,
            UUID paymentAttemptId, UUID authorizationId) {
    }

    private record OperatorAccess(UUID id, String secret) {
    }
}
