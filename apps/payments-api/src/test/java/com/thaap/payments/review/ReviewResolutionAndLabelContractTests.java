package com.thaap.payments.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.support.DatabaseCleaner;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class ReviewResolutionAndLabelContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void analystCanClaimAnnotateAndCancelReportFraudWithoutRewritingDecisionOrCreatingMoneyTruth()
            throws Exception {
        MerchantAccess merchant = createMerchant("review-label-owner");
        UUID customerId = createCustomer(merchant, "review_label@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId);
        ReviewFixture review = createReview(merchant, customerId, paymentMethodId);
        OperatorAccess analyst = createOperator(merchant, "operator-create", "ANALYST");

        JsonNode replay = createOperatorResponse(merchant, "operator-create", "ANALYST");
        assertThat(replay.get("id").asText()).isEqualTo(analyst.id().toString());
        assertThat(replay.get("credential").asText()).isEqualTo(analyst.secret());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_operators", Integer.class)).isOne();

        mockMvc.perform(get("/risk/reviews").header(API_KEY, merchant.apiKey()))
                .andExpect(status().isUnauthorized());
        JsonNode queue = objectMapper.readTree(mockMvc.perform(get("/risk/reviews")
                        .param("status", "OPEN").header(RISK_KEY, analyst.secret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).get("id").asText()).isEqualTo(review.reviewCaseId().toString());

        JsonNode assigned = objectMapper.readTree(mockMvc.perform(post("/risk/reviews/{id}/assign",
                        review.reviewCaseId()).header(RISK_KEY, analyst.secret())
                        .header(IDEMPOTENCY_KEY, "assign-review").contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedAssignmentVersion": 0}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(assigned.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(assigned.get("assignmentVersion").asLong()).isEqualTo(1L);
        assertThat(assigned.get("assignedOperatorId").asText()).isEqualTo(analyst.id().toString());

        mockMvc.perform(post("/risk/reviews/{id}/annotations", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "annotate-review")
                        .contentType(APPLICATION_JSON).content("""
                                {"code":"IDENTITY_MISMATCH"}
                                """))
                .andExpect(status().isCreated());

        int authorizationCountBefore = count("authorizations");
        String evidenceTime = Instant.now().minusSeconds(5).toString();
        JsonNode resolved = objectMapper.readTree(mockMvc.perform(post("/risk/reviews/{id}/resolve",
                        review.reviewCaseId()).header(RISK_KEY, analyst.secret())
                        .header(IDEMPOTENCY_KEY, "resolve-review").contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "outcome":"CANCEL_AND_REPORT_FRAUD",
                                  "classification":"CONFIRMED_FRAUD",
                                  "fraudType":"ACCOUNT_TAKEOVER",
                                  "reasonCode":"ACCOUNT_TAKEOVER_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(evidenceTime)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(resolved.get("outcome").asText()).isEqualTo("CANCEL_AND_REPORT_FRAUD");
        assertThat(resolved.get("label").get("fraudType").asText()).isEqualTo("ACCOUNT_TAKEOVER");
        assertThat(resolved.get("label").get("taxonomyVersion").asText())
                .isEqualTo("fraud_taxonomy_v1");
        assertThat(jdbcTemplate.queryForObject("SELECT taxonomy_version FROM fraud_labels WHERE id = ?",
                String.class, UUID.fromString(resolved.get("label").get("id").asText())))
                .isEqualTo("fraud_taxonomy_v1");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_intents WHERE id = ?",
                String.class, review.paymentIntentId())).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_attempts WHERE id = ?",
                String.class, review.paymentAttemptId())).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM manual_review_cases WHERE id = ?",
                String.class, review.reviewCaseId())).isEqualTo("RESOLVED");
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM fraud_decisions WHERE id = ?",
                String.class, review.fraudDecisionId())).isEqualTo("REVIEW");
        assertThat(count("review_assignment_events")).isOne();
        assertThat(count("review_annotations")).isOne();
        assertThat(count("review_resolutions")).isOne();
        assertThat(count("fraud_labels")).isOne();
        assertThat(count("authorizations")).isEqualTo(authorizationCountBefore);
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("refunds")).isZero();
    }

    @Test
    void viewerCannotMutateAndDisabledAnalystCannotUsePreviouslyValidCredential() throws Exception {
        MerchantAccess merchant = createMerchant("review-label-authz");
        OperatorAccess viewer = createOperator(merchant, "viewer-create", "VIEWER");
        OperatorAccess analyst = createOperator(merchant, "analyst-create", "ANALYST");
        UUID unknownReviewId = UUID.randomUUID();

        mockMvc.perform(get("/risk/reviews").header(RISK_KEY, viewer.secret()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/risk/reviews/{id}/assign", unknownReviewId)
                        .header(RISK_KEY, viewer.secret()).header(IDEMPOTENCY_KEY, "viewer-assign")
                        .contentType(APPLICATION_JSON).content("{\"expectedAssignmentVersion\":0}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/risk/operators/{id}/disable", analyst.id())
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, "disable-analyst"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/risk/reviews").header(RISK_KEY, analyst.secret()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void completedMutationReplayIsBoundToTheOriginalOperatorAndCredentialLifecycle() throws Exception {
        MerchantAccess merchant = createMerchant("review-actor-bound-idempotency");
        UUID customerId = createCustomer(merchant, "actor-bound@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId, "actor-bound-card");
        ReviewFixture review = createReview(merchant, customerId, paymentMethodId, "actor-bound-review");
        OperatorAccess firstAnalyst = createOperator(merchant, "actor-bound-first", "ANALYST");
        OperatorAccess secondAnalyst = createOperator(merchant, "actor-bound-second", "ANALYST");

        String firstResponse = assign(firstAnalyst, review.reviewCaseId(),
                "shared-operator-key", 0L, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assign(firstAnalyst, review.reviewCaseId(), "shared-operator-key", 0L, null)
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(firstResponse));

        assign(secondAnalyst, review.reviewCaseId(), "shared-operator-key", 0L, null)
                .andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM idempotency_records
                 WHERE idempotency_key = ?
                   AND principal_type = 'RISK_OPERATOR'
                   AND principal_id = ?
                """, Integer.class, "shared-operator-key", firstAnalyst.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM idempotency_records
                 WHERE idempotency_key = ?
                   AND principal_type = 'RISK_OPERATOR'
                   AND principal_id = ?
                """, Integer.class, "shared-operator-key", secondAnalyst.id())).isZero();
    }

    @Test
    void cancelResolutionCreatesNoLabelAndReplayCannotChangeCommittedTerminalTruth() throws Exception {
        MerchantAccess merchant = createMerchant("review-label-cancel-only");
        UUID customerId = createCustomer(merchant, "cancel-only@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId);
        ReviewFixture review = createReview(merchant, customerId, paymentMethodId);
        OperatorAccess analyst = createOperator(merchant, "cancel-only-analyst", "ANALYST");

        assign(analyst, review.reviewCaseId(), "cancel-only-assign", 0L, null)
                .andExpect(status().isOk());
        String evidenceTime = Instant.now().minusSeconds(5).toString();
        String cancelBody = """
                {
                  "outcome":"CANCEL",
                  "classification":"INCONCLUSIVE",
                  "reasonCode":"RISK_TOO_HIGH",
                  "evidenceOccurredAt":"%s"
                }
                """.formatted(evidenceTime);
        String firstResponse = mockMvc.perform(post("/risk/reviews/{id}/resolve", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "cancel-only-resolve")
                        .contentType(APPLICATION_JSON).content(cancelBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replayResponse = mockMvc.perform(post("/risk/reviews/{id}/resolve", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "cancel-only-resolve")
                        .contentType(APPLICATION_JSON).content(cancelBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(replayResponse)).isEqualTo(objectMapper.readTree(firstResponse));
        assertThat(objectMapper.readTree(firstResponse).get("label").isNull()).isTrue();
        assertThat(count("review_resolutions")).isOne();
        assertThat(count("fraud_labels")).isZero();
        mockMvc.perform(post("/risk/reviews/{id}/resolve", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "cancel-only-resolve")
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "outcome":"CANCEL_AND_REPORT_FRAUD",
                                  "classification":"CONFIRMED_FRAUD",
                                  "fraudType":"CARD_TESTING",
                                  "reasonCode":"CARD_TESTING_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(evidenceTime)))
                .andExpect(status().isConflict());
        assertThat(count("review_resolutions")).isOne();
        assertThat(count("fraud_labels")).isZero();
    }

    @Test
    void roleAssignmentAndTenantSubstitutionPartitionsAreMissingEquivalent() throws Exception {
        MerchantAccess owner = createMerchant("review-label-tenant-owner");
        MerchantAccess foreign = createMerchant("review-label-tenant-foreign");
        UUID customerId = createCustomer(owner, "tenant-owner@example.com");
        UUID paymentMethodId = createPaymentMethod(owner, customerId);
        ReviewFixture review = createReview(owner, customerId, paymentMethodId);
        OperatorAccess ownerAnalyst = createOperator(owner, "owner-analyst", "ANALYST");
        OperatorAccess secondAnalyst = createOperator(owner, "second-analyst", "ANALYST");
        OperatorAccess manager = createOperator(owner, "owner-manager", "RISK_MANAGER");
        OperatorAccess foreignManager = createOperator(foreign, "foreign-manager", "RISK_MANAGER");

        mockMvc.perform(get("/risk/reviews/{id}", review.reviewCaseId())
                        .header(RISK_KEY, foreignManager.secret()))
                .andExpect(status().isNotFound());
        assign(foreignManager, review.reviewCaseId(), "foreign-assign", 0L, null)
                .andExpect(status().isNotFound());
        assign(ownerAnalyst, review.reviewCaseId(), "analyst-assign-other", 0L, secondAnalyst.id())
                .andExpect(status().isNotFound());

        assign(ownerAnalyst, review.reviewCaseId(), "owner-self-assign", 0L, null)
                .andExpect(status().isOk());
        assign(secondAnalyst, review.reviewCaseId(), "assignment-theft", 1L, null)
                .andExpect(status().isNotFound());
        assign(manager, review.reviewCaseId(), "manager-reassign", 1L, secondAnalyst.id())
                .andExpect(status().isOk());
        mockMvc.perform(post("/risk/reviews/{id}/release", review.reviewCaseId())
                        .header(RISK_KEY, ownerAnalyst.secret()).header(IDEMPOTENCY_KEY, "stale-owner-release")
                        .contentType(APPLICATION_JSON).content("{\"expectedAssignmentVersion\":2}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/risk/reviews/{id}/release", review.reviewCaseId())
                        .header(RISK_KEY, secondAnalyst.secret()).header(IDEMPOTENCY_KEY, "current-owner-release")
                        .contentType(APPLICATION_JSON).content("{\"expectedAssignmentVersion\":2}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT assignment_version FROM manual_review_cases WHERE id = ?", Long.class,
                review.reviewCaseId())).isEqualTo(3L);
        assertThat(count("review_assignment_events")).isEqualTo(3);
    }

    @Test
    void closedRequestGrammarRejectsInvalidResolutionAnnotationAndPaginationPartitions() throws Exception {
        MerchantAccess merchant = createMerchant("review-label-grammar");
        UUID customerId = createCustomer(merchant, "grammar@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId);
        ReviewFixture review = createReview(merchant, customerId, paymentMethodId);
        OperatorAccess analyst = createOperator(merchant, "grammar-analyst", "ANALYST");

        mockMvc.perform(get("/risk/reviews").header(RISK_KEY, analyst.secret()).param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/risk/reviews").header(RISK_KEY, analyst.secret()).param("limit", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/risk/reviews").header(RISK_KEY, analyst.secret())
                        .param("starting_after", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/risk/reviews").header(RISK_KEY, analyst.secret())
                        .param("starting_after", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        assign(analyst, review.reviewCaseId(), "grammar-assign", 0L, null)
                .andExpect(status().isOk());
        mockMvc.perform(post("/risk/reviews/{id}/annotations", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "wrong-media-type")
                        .contentType("text/plain").content("IDENTITY_MISMATCH"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post("/risk/reviews/{id}/annotations", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "unknown-field")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"IDENTITY_MISMATCH\",\"note\":\"must not be accepted\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/reviews/{id}/annotations", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "unknown-annotation")
                        .contentType(APPLICATION_JSON).content("{\"code\":\"FREE_FORM_NOTES\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/reviews/{id}/annotations", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "nested-annotation")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":{\"value\":\"IDENTITY_MISMATCH\"}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/reviews/{id}/resolve", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "invalid-cancel-semantics")
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "outcome":"CANCEL",
                                  "classification":"CONFIRMED_FRAUD",
                                  "fraudType":"CARD_TESTING",
                                  "reasonCode":"CARD_TESTING_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(Instant.now().minusSeconds(1))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/reviews/{id}/resolve", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "invalid-cancel-reason")
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "outcome":"CANCEL",
                                  "classification":"INCONCLUSIVE",
                                  "reasonCode":"CARD_TESTING_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(Instant.now().minusSeconds(1))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/reviews/{id}/resolve", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "mismatched-fraud-reason")
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "outcome":"CANCEL_AND_REPORT_FRAUD",
                                  "classification":"CONFIRMED_FRAUD",
                                  "fraudType":"CARD_TESTING",
                                  "reasonCode":"ACCOUNT_TAKEOVER_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(Instant.now().minusSeconds(1))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/reviews/{id}/resolve", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "future-evidence")
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "outcome":"CANCEL_AND_REPORT_FRAUD",
                                  "classification":"CONFIRMED_FRAUD",
                                  "fraudType":"CARD_TESTING",
                                  "reasonCode":"CARD_TESTING_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(Instant.now().plusSeconds(3600))))
                .andExpect(status().isBadRequest());
        assertThat(count("review_annotations")).isZero();
        assertThat(count("review_resolutions")).isZero();
        assertThat(count("fraud_labels")).isZero();
    }

    @Test
    void cursorPaginationDoesNotSkipOrDuplicateReviewsWithEqualCreationTimestamps() throws Exception {
        MerchantAccess merchant = createMerchant("review-label-equal-cursor");
        OperatorAccess analyst = createOperator(merchant, "equal-cursor-analyst", "ANALYST");
        UUID firstCustomerId = createCustomer(merchant, "equal-cursor-1@example.com");
        UUID secondCustomerId = createCustomer(merchant, "equal-cursor-2@example.com");
        UUID firstPaymentMethodId = createPaymentMethod(
                merchant, firstCustomerId, "equal-cursor-card-1");
        UUID secondPaymentMethodId = createPaymentMethod(
                merchant, secondCustomerId, "equal-cursor-card-2");
        ReviewFixture firstReview = createReview(
                merchant, firstCustomerId, firstPaymentMethodId, "equal-cursor-review-1");
        ReviewFixture secondReview = createReview(
                merchant, secondCustomerId, secondPaymentMethodId, "equal-cursor-review-2");
        Instant sharedCreatedAt = Instant.parse("2026-07-13T09:30:00.123456Z");
        jdbcTemplate.update("UPDATE manual_review_cases SET created_at = ?", sharedCreatedAt);

        JsonNode firstPage = objectMapper.readTree(mockMvc.perform(get("/risk/reviews")
                        .header(RISK_KEY, analyst.secret()).param("status", "OPEN").param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(firstPage).hasSize(1);
        String cursor = firstPage.get(0).get("id").asText();
        JsonNode secondPage = objectMapper.readTree(mockMvc.perform(get("/risk/reviews")
                        .header(RISK_KEY, analyst.secret()).param("status", "OPEN").param("limit", "1")
                        .param("starting_after", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(secondPage).hasSize(1);
        assertThat(List.of(cursor, secondPage.get(0).get("id").asText()))
                .containsExactlyInAnyOrder(firstReview.reviewCaseId().toString(),
                        secondReview.reviewCaseId().toString());
    }

    @Test
    void operatorSecretIsOneBoundaryValueAndObservabilityUsesOnlyClosedDimensions() throws Exception {
        MerchantAccess merchant = createMerchant("review-label-secret-boundary");
        OperatorAccess analyst = createOperator(merchant, "secret-boundary-analyst", "ANALYST");

        Map<String, Object> credential = jdbcTemplate.queryForMap("""
                SELECT secret_prefix, secret_sha256, secret_identity_hmac, replay_ciphertext
                FROM risk_operator_credentials
                WHERE risk_operator_id = ?
                """, analyst.id());
        assertThat(credential.values()).allSatisfy(value ->
                assertThat(String.valueOf(value)).doesNotContain(analyst.secret()));
        String persistedText = String.join("", jdbcTemplate.queryForList("""
                SELECT CAST(before_json AS VARCHAR) AS persisted_value FROM audit_logs
                UNION ALL SELECT CAST(after_json AS VARCHAR) AS persisted_value FROM audit_logs
                """, String.class));
        assertThat(persistedText).doesNotContain(analyst.secret());
        mockMvc.perform(get("/risk/reviews").header(API_KEY, merchant.apiKey()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/payment_intents/{id}", UUID.randomUUID())
                        .header(RISK_KEY, analyst.secret()))
                .andExpect(status().isUnauthorized());

        List<Meter> riskMeters = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("thaap.risk.operations"))
                .toList();
        assertThat(riskMeters).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                    .containsExactly("operation", "outcome", "transaction");
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getValue()).doesNotContain(analyst.secret());
                assertThat(tag.getValue()).doesNotContain(analyst.id().toString());
            });
        });
    }

    @Test
    void operatorCreationReplayChangedBodyExpiryAndCredentialGrammarAreClosed() throws Exception {
        MerchantAccess merchant = createMerchant("review-label-credential-lifecycle");
        OperatorAccess analyst = createOperator(merchant, "credential-lifecycle", "ANALYST");
        JsonNode replay = createOperatorResponse(merchant, "credential-lifecycle", "ANALYST");
        assertThat(replay.get("credential").asText()).isEqualTo(analyst.secret());
        mockMvc.perform(post("/risk/operators").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "credential-lifecycle")
                        .contentType(APPLICATION_JSON).content("{\"role\":\"RISK_MANAGER\"}"))
                .andExpect(status().isConflict());
        assertThat(count("risk_operators")).isOne();
        mockMvc.perform(get("/risk/reviews").header(RISK_KEY, "  " + analyst.secret() + "  "))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/risk/reviews").header(RISK_KEY, analyst.secret() + "é"))
                .andExpect(status().isUnauthorized());

        String oldPepper = "test-hash-pepper-for-sensitive-value-contracts";
        String previousPepper = "test-v1=" + Base64.getEncoder().encodeToString(
                oldPepper.getBytes(StandardCharsets.UTF_8));
        SensitiveValueHasher rotatedHasher = new SensitiveValueHasher(
                "review-label-rotated-pepper", "test-v2", previousPepper);
        String storedCredentialIdentity = jdbcTemplate.queryForObject("""
                SELECT secret_identity_hmac FROM risk_operator_credentials
                WHERE risk_operator_id = ?
                """, String.class, analyst.id());
        String storedCreationIdentity = jdbcTemplate.queryForObject("""
                SELECT creation_identity_hmac FROM risk_operator_credentials
                WHERE risk_operator_id = ?
                """, String.class, analyst.id());
        assertThat(rotatedHasher.hmacSha256Keyring("risk.operator.credential", analyst.secret()))
                .extracting(SensitiveValueHasher.VersionedDigest::digest)
                .contains(storedCredentialIdentity);
        assertThat(rotatedHasher.hmacSha256Keyring(
                        "risk.operator.creation.idempotency", "credential-lifecycle"))
                .extracting(SensitiveValueHasher.VersionedDigest::digest)
                .contains(storedCreationIdentity);

        jdbcTemplate.update("""
                UPDATE risk_operator_credentials
                SET replay_expires_at = ?
                WHERE risk_operator_id = ?
                """, Instant.now().minusSeconds(1), analyst.id());
        mockMvc.perform(post("/risk/operators").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, "credential-lifecycle")
                        .contentType(APPLICATION_JSON).content("{\"role\":\"ANALYST\"}"))
                .andExpect(status().isConflict());
        assertThat(count("risk_operators")).isOne();
    }

    @Test
    void newRiskWebhookEventsRequireExplicitSubscriptionConsent() throws Exception {
        MerchantAccess merchant = createMerchant("review-label-webhook-consent");
        UUID legacyStyleEndpoint = createWebhookEndpoint(merchant, "legacy-events-only",
                "[\"refund.created\"]", 9192);
        UUID explicitRiskEndpoint = createWebhookEndpoint(merchant, "explicit-risk-events",
                "[\"payment_intent.canceled\",\"review.resolved\",\"fraud_label.created\"]", 9193);
        UUID customerId = createCustomer(merchant, "webhook-consent@example.com");
        UUID paymentMethodId = createPaymentMethod(merchant, customerId);
        ReviewFixture review = createReview(merchant, customerId, paymentMethodId);
        OperatorAccess analyst = createOperator(merchant, "webhook-consent-analyst", "ANALYST");

        assign(analyst, review.reviewCaseId(), "webhook-consent-assign", 0L, null)
                .andExpect(status().isOk());
        mockMvc.perform(post("/risk/reviews/{id}/resolve", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "webhook-consent-resolve")
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "outcome":"CANCEL_AND_REPORT_FRAUD",
                                  "classification":"CONFIRMED_FRAUD",
                                  "fraudType":"CARD_TESTING",
                                  "reasonCode":"CARD_TESTING_CONFIRMED",
                                  "evidenceOccurredAt":"%s"
                                }
                                """.formatted(Instant.now().minusSeconds(5))))
                .andExpect(status().isOk());

        List<String> legacySubscriptions = jdbcTemplate.queryForList("""
                SELECT event_type FROM webhook_endpoint_event_subscriptions
                WHERE webhook_endpoint_id = ? ORDER BY event_type
                """, String.class, legacyStyleEndpoint);
        assertThat(legacySubscriptions).containsExactly("refund.created");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM webhook_deliveries
                WHERE webhook_endpoint_id = ?
                  AND webhook_event_id IN (
                    SELECT id FROM webhook_events
                    WHERE event_type IN ('payment_intent.canceled', 'review.resolved', 'fraud_label.created')
                  )
                """, Integer.class, legacyStyleEndpoint)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM webhook_deliveries
                WHERE webhook_endpoint_id = ?
                  AND webhook_event_id IN (
                    SELECT id FROM webhook_events
                    WHERE event_type IN ('payment_intent.canceled', 'review.resolved', 'fraud_label.created')
                  )
                """, Integer.class, explicitRiskEndpoint)).isEqualTo(3);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new MerchantAccess(UUID.fromString(response.get("id").asText()), response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key, String role) throws Exception {
        JsonNode response = createOperatorResponse(merchant, key, role);
        return new OperatorAccess(UUID.fromString(response.get("id").asText()), response.get("credential").asText());
    }

    private JsonNode createOperatorResponse(MerchantAccess merchant, String key, String role) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/risk/operators").header(API_KEY, merchant.apiKey())
                        .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createCustomer(MerchantAccess merchant, String email) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/customers")
                        .header(API_KEY, merchant.apiKey()).contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId) throws Exception {
        return createPaymentMethod(merchant, customerId, "review-card");
    }

    private UUID createPaymentMethod(MerchantAccess merchant, UUID customerId, String key) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/payment_methods")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","cardNumber":"4999990000000003"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private ReviewFixture createReview(MerchantAccess merchant, UUID customerId, UUID paymentMethodId)
            throws Exception {
        return createReview(merchant, customerId, paymentMethodId, "review");
    }

    private ReviewFixture createReview(MerchantAccess merchant, UUID customerId, UUID paymentMethodId,
            String keyPrefix) throws Exception {
        JsonNode intent = objectMapper.readTree(mockMvc.perform(post("/payment_intents")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, keyPrefix + "-intent")
                        .contentType(APPLICATION_JSON).content("""
                                {"customerId":"%s","amount":99999,"currency":"USD","captureMethod":"MANUAL"}
                                """.formatted(customerId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID paymentIntentId = UUID.fromString(intent.get("id").asText());
        confirm(merchant, paymentIntentId, paymentMethodId, keyPrefix + "-confirm-1");
        confirm(merchant, paymentIntentId, paymentMethodId, keyPrefix + "-confirm-2");
        JsonNode attempt = confirm(merchant, paymentIntentId, paymentMethodId, keyPrefix + "-confirm-3");
        Map<String, Object> review = jdbcTemplate.queryForMap(
                "SELECT id FROM manual_review_cases WHERE payment_intent_id = ?", paymentIntentId);
        return new ReviewFixture(paymentIntentId, UUID.fromString(attempt.get("id").asText()),
                UUID.fromString(attempt.get("fraudDecisionId").asText()), (UUID) review.get("ID"));
    }

    private JsonNode confirm(MerchantAccess merchant, UUID paymentIntentId, UUID paymentMethodId, String key)
            throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/payment_intents/{id}/confirm", paymentIntentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("{\"paymentMethodId\":\"" + paymentMethodId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions assign(OperatorAccess operator,
            UUID reviewCaseId, String idempotencyKey, long expectedVersion, UUID targetOperatorId)
            throws Exception {
        String operatorField = targetOperatorId == null
                ? "" : ",\"operatorId\":\"" + targetOperatorId + "\"";
        return mockMvc.perform(post("/risk/reviews/{id}/assign", reviewCaseId)
                .header(RISK_KEY, operator.secret()).header(IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(APPLICATION_JSON)
                .content("{\"expectedAssignmentVersion\":" + expectedVersion + operatorField + "}"));
    }

    private UUID createWebhookEndpoint(MerchantAccess merchant, String key,
            String enabledEventsJson, int port) throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/webhook_endpoints")
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON).content("""
                                {
                                  "url":"http://127.0.0.1:%d/review-label",
                                  "enabledEvents":%s
                                }
                                """.formatted(port, enabledEventsJson)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record MerchantAccess(UUID id, String apiKey) {
    }
    private record OperatorAccess(UUID id, String secret) {
    }
    private record ReviewFixture(UUID paymentIntentId, UUID paymentAttemptId,
            UUID fraudDecisionId, UUID reviewCaseId) {
    }
}
