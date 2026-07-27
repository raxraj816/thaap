package com.thaap.payments.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.time.Instant;
import java.util.LinkedHashMap;
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
class AnalystWorkspaceContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String RISK_KEY = "X-Risk-Operator-Key";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_TOKEN = "X-Admin-Bootstrap-Token";

    @Autowired private WebApplicationContext context;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private ReviewNoteService reviewNoteService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        databaseCleaner.clean();
    }

    @Test
    void operatorSessionProjectsOnlyServerDerivedIdentityAndRole() throws Exception {
        MerchantAccess merchant = createMerchant("workspace-session-owner");
        MerchantAccess foreign = createMerchant("workspace-session-foreign");
        OperatorAccess analyst = createOperator(merchant, "session-analyst", "ANALYST");
        OperatorAccess foreignManager = createOperator(foreign, "session-manager", "RISK_MANAGER");

        JsonNode session = json(get("/risk/operators/me").header(RISK_KEY, analyst.secret()), 200);
        JsonNode foreignSession = json(get("/risk/operators/me")
                .header(RISK_KEY, foreignManager.secret()), 200);

        assertThat(session.get("id").asText()).isEqualTo(analyst.id().toString());
        assertThat(session.get("role").asText()).isEqualTo("ANALYST");
        assertThat(session.get("operatorCode").asText()).isNotBlank();
        assertThat(session.toString()).doesNotContain(
                merchant.id().toString(), analyst.secret(), foreign.id().toString());
        assertThat(foreignSession.get("id").asText()).isEqualTo(foreignManager.id().toString());
        assertThat(foreignSession.get("role").asText()).isEqualTo("RISK_MANAGER");

        mockMvc.perform(get("/risk/operators/me").header(RISK_KEY, "invalid-risk-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void typedQueueFiltersAndHmacCursorStayTenantAndMeaningBound() throws Exception {
        MerchantAccess owner = createMerchant("workspace-queue-owner");
        MerchantAccess foreign = createMerchant("workspace-queue-foreign");
        OperatorAccess viewer = createOperator(owner, "queue-viewer", "VIEWER");
        OperatorAccess foreignViewer = createOperator(foreign, "foreign-queue-viewer", "VIEWER");
        ReviewFixture first = createReview(owner, "queue-first");
        ReviewFixture second = createReview(owner, "queue-second");
        createReview(foreign, "queue-foreign");

        Map<String, Long> before = domainTruthCounts();
        String body = """
                {
                  "filter":{"statuses":["OPEN"],"reviewStages":["PRE_AUTHORIZATION"],
                    "decisionActions":["REVIEW"],"trafficOrigins":["MERCHANT_API"]},
                  "sort":"CREATED_AT_ASC"
                }
                """;
        JsonNode pageOne = json(post("/risk/workspace/reviews/search")
                .header(RISK_KEY, viewer.secret()).param("limit", "1")
                .contentType(APPLICATION_JSON).content(body), 200);
        assertThat(pageOne.get("rows")).hasSize(1);
        assertThat(pageOne.get("nextCursor").asText()).isNotBlank();
        JsonNode pageTwo = json(post("/risk/workspace/reviews/search")
                .header(RISK_KEY, viewer.secret()).param("limit", "1")
                .param("cursor", pageOne.get("nextCursor").asText())
                .contentType(APPLICATION_JSON).content(body), 200);
        assertThat(pageTwo.get("rows")).hasSize(1);
        assertThat(List.of(pageOne.at("/rows/0/id").asText(), pageTwo.at("/rows/0/id").asText()))
                .containsExactlyInAnyOrder(first.reviewCaseId().toString(), second.reviewCaseId().toString());

        mockMvc.perform(post("/risk/workspace/reviews/search")
                        .header(RISK_KEY, viewer.secret()).param("limit", "1")
                        .param("cursor", pageOne.get("nextCursor").asText())
                        .contentType(APPLICATION_JSON).content(body.replace("OPEN", "ASSIGNED")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/workspace/reviews/search")
                        .header(RISK_KEY, foreignViewer.secret()).param("limit", "1")
                        .param("cursor", pageOne.get("nextCursor").asText())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        String originalCursor = pageOne.get("nextCursor").asText();
        char replacement = originalCursor.charAt(originalCursor.length() - 1) == '0' ? '1' : '0';
        String tampered = originalCursor.substring(0, originalCursor.length() - 1) + replacement;
        mockMvc.perform(post("/risk/workspace/reviews/search")
                        .header(RISK_KEY, viewer.secret()).param("cursor", tampered)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/workspace/reviews/search")
                        .header(RISK_KEY, viewer.secret()).param("limit", "101")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(domainTruthCounts()).isEqualTo(before);
    }

    @Test
    void savedSearchMeaningAndPrivateSharedAuthorizationFailClosed() throws Exception {
        MerchantAccess merchant = createMerchant("workspace-search-owner");
        OperatorAccess viewer = createOperator(merchant, "search-viewer", "VIEWER");
        OperatorAccess otherViewer = createOperator(merchant, "search-other-viewer", "VIEWER");
        OperatorAccess manager = createOperator(merchant, "search-manager", "RISK_MANAGER");

        String privateBody = savedSearchBody("My open reviews", "PRIVATE");
        JsonNode privateSearch = json(post("/risk/workspace/saved-searches")
                .header(RISK_KEY, viewer.secret()).header(IDEMPOTENCY_KEY, "private-search")
                .contentType(APPLICATION_JSON).content(privateBody), 201);
        UUID privateId = UUID.fromString(privateSearch.get("id").asText());
        assertThat(privateSearch.get("filterSchemaVersion").asText())
                .isEqualTo(ReviewQueueFilterCodec.SCHEMA_VERSION);
        assertThat(privateSearch.get("definitionDigest").asText()).hasSize(64);

        mockMvc.perform(post("/risk/workspace/saved-searches")
                        .header(RISK_KEY, viewer.secret()).header(IDEMPOTENCY_KEY, "bad-share")
                        .contentType(APPLICATION_JSON)
                        .content(savedSearchBody("Viewer shared", "MERCHANT_SHARED")))
                .andExpect(status().isForbidden());
        JsonNode shared = json(post("/risk/workspace/saved-searches")
                .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "manager-share")
                .contentType(APPLICATION_JSON)
                .content(savedSearchBody("Merchant priority queue", "MERCHANT_SHARED")), 201);

        JsonNode ownerList = json(get("/risk/workspace/saved-searches")
                .header(RISK_KEY, viewer.secret()), 200);
        assertThat(ownerList).hasSize(2);
        JsonNode otherList = json(get("/risk/workspace/saved-searches")
                .header(RISK_KEY, otherViewer.secret()), 200);
        assertThat(otherList).hasSize(1);
        assertThat(otherList.get(0).get("id").asText()).isEqualTo(shared.get("id").asText());

        mockMvc.perform(post("/risk/workspace/saved-searches/{id}", privateId)
                        .header(RISK_KEY, otherViewer.secret()).header(IDEMPOTENCY_KEY, "foreign-update")
                        .contentType(APPLICATION_JSON).content(updateSearchBody(1, "Stolen")))
                .andExpect(status().isNotFound());
        String replayOne = mockMvc.perform(post("/risk/workspace/saved-searches/{id}/disable", privateId)
                        .header(RISK_KEY, viewer.secret()).header(IDEMPOTENCY_KEY, "disable-private")
                        .contentType(APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replayTwo = mockMvc.perform(post("/risk/workspace/saved-searches/{id}/disable", privateId)
                        .header(RISK_KEY, viewer.secret()).header(IDEMPOTENCY_KEY, "disable-private")
                        .contentType(APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replayTwo)).isEqualTo(objectMapper.readTree(replayOne));
        assertThat(jdbcTemplate.queryForObject("SELECT lifecycle_status FROM review_saved_searches WHERE id = ?",
                String.class, privateId)).isEqualTo("DISABLED");

        UUID sharedId = UUID.fromString(shared.get("id").asText());
        jdbcTemplate.update("UPDATE review_saved_searches SET filter_schema_version = 'unsupported_v2' WHERE id = ?",
                sharedId);
        JsonNode upgradeError = json(post("/risk/workspace/saved-searches/{id}/execute", sharedId)
                .header(RISK_KEY, viewer.secret()), 409);
        assertThat(upgradeError.get("error").asText()).isEqualTo("saved_search_upgrade_required");
    }

    @Test
    void protectedNotesEncryptReplayCorrectRedactAndNeverChangeDomainTruth() throws Exception {
        MerchantAccess merchant = createMerchant("workspace-note-owner");
        ReviewFixture review = createReview(merchant, "workspace-note-review");
        OperatorAccess analyst = createOperator(merchant, "note-analyst", "ANALYST");
        OperatorAccess otherAnalyst = createOperator(merchant, "note-other-analyst", "ANALYST");
        OperatorAccess manager = createOperator(merchant, "note-manager", "RISK_MANAGER");
        OperatorAccess viewer = createOperator(merchant, "note-viewer", "VIEWER");
        assign(analyst, review.reviewCaseId(), "assign-note-case", 0L).andExpect(status().isOk());
        Map<String, Long> before = domainTruthCounts();
        String plaintext = "Customer confirmed the device change by phone";
        String noteBody = objectMapper.writeValueAsString(Map.of(
                "expectedAssignmentVersion", 1,
                "content", plaintext));

        mockMvc.perform(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                        .header(RISK_KEY, viewer.secret()).header(IDEMPOTENCY_KEY, "viewer-note")
                        .contentType(APPLICATION_JSON).content(noteBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                        .header(RISK_KEY, otherAnalyst.secret()).header(IDEMPOTENCY_KEY, "other-note")
                        .contentType(APPLICATION_JSON).content(noteBody))
                .andExpect(status().isForbidden());

        String firstResponse = mockMvc.perform(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "create-note")
                        .contentType(APPLICATION_JSON).content(noteBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replayResponse = mockMvc.perform(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                        .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "create-note")
                        .contentType(APPLICATION_JSON).content(noteBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replayResponse)).isEqualTo(objectMapper.readTree(firstResponse));
        JsonNode first = objectMapper.readTree(firstResponse);
        UUID firstNoteId = UUID.fromString(first.get("id").asText());
        assertThat(first.get("content").asText()).isEqualTo(plaintext);
        Map<String, Object> persisted = jdbcTemplate.queryForMap(
                "SELECT ciphertext, nonce, key_version FROM review_notes WHERE id = ?", firstNoteId);
        assertThat(persisted.get("CIPHERTEXT").toString()).doesNotContain(plaintext);
        assertThat(persisted.get("NONCE").toString()).hasSize(16);
        assertThat(persisted.get("KEY_VERSION")).isEqualTo("test-v1");
        String replayStored = jdbcTemplate.queryForObject("""
                SELECT response_body FROM idempotency_records
                 WHERE principal_id = ? AND idempotency_key = 'create-note'
                """, String.class, analyst.id());
        assertThat(replayStored).contains("sensitive-replay-v1").doesNotContain(plaintext);

        String correctionBody = objectMapper.writeValueAsString(Map.of(
                "expectedAssignmentVersion", 1,
                "content", "Correction: contact was through the verified support channel",
                "supersedesNoteId", firstNoteId));
        JsonNode correction = json(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "correct-note")
                .contentType(APPLICATION_JSON).content(correctionBody), 201);
        assertThat(correction.get("supersedesNoteId").asText()).isEqualTo(firstNoteId.toString());

        mockMvc.perform(get("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                        .header(RISK_KEY, viewer.secret()).param("expectedAssignmentVersion", "1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                        .header(RISK_KEY, otherAnalyst.secret())
                        .param("expectedAssignmentVersion", "1"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'review_note.accessed'",
                Integer.class)).isZero();

        JsonNode managerNotes = json(get("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                .header(RISK_KEY, manager.secret())
                .header(RequestIdFilter.REQUEST_ID_HEADER, "manager-note-read")
                .param("expectedAssignmentVersion", "1"), 200);
        assertThat(managerNotes).hasSize(2);
        assertThat(managerNotes.toString()).contains(plaintext);
        assertReviewNoteAccessAudit(
                "manager-note-read", review.reviewCaseId(), 2, 2, plaintext);

        JsonNode redacted = json(post("/risk/workspace/reviews/{reviewId}/notes/{noteId}/redact",
                        review.reviewCaseId(), firstNoteId)
                .header(RISK_KEY, manager.secret()).header(IDEMPOTENCY_KEY, "redact-note"), 200);
        assertThat(redacted.get("lifecycleStatus").asText()).isEqualTo("REDACTED");
        assertThat(redacted.get("content").isNull()).isTrue();
        Map<String, Object> destroyed = jdbcTemplate.queryForMap(
                "SELECT ciphertext, nonce, key_version, lifecycle_status FROM review_notes WHERE id = ?",
                firstNoteId);
        assertThat(destroyed.get("CIPHERTEXT")).isNull();
        assertThat(destroyed.get("NONCE")).isNull();
        assertThat(destroyed.get("KEY_VERSION")).isNull();
        assertThat(destroyed.get("LIFECYCLE_STATUS")).isEqualTo("REDACTED");
        assertThat(count("review_note_redaction_events")).isOne();

        JsonNode afterRedaction = json(get("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                .header(RISK_KEY, analyst.secret())
                .header(RequestIdFilter.REQUEST_ID_HEADER, "analyst-note-read-after-redaction")
                .param("expectedAssignmentVersion", "1"), 200);
        JsonNode redactedRow = java.util.stream.StreamSupport.stream(afterRedaction.spliterator(), false)
                .filter(node -> node.get("id").asText().equals(firstNoteId.toString())).findFirst().orElseThrow();
        assertThat(redactedRow.get("content").isNull()).isTrue();
        assertReviewNoteAccessAudit("analyst-note-read-after-redaction",
                review.reviewCaseId(), 2, 1, plaintext);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'review_note.accessed'",
                Integer.class)).isEqualTo(2);
        assertThat(domainTruthCounts()).isEqualTo(before);
        assertNoPlaintextSink(plaintext);
    }

    @Test
    void secretPatternAndTenantSubstitutionAreRejectedBeforeCiphertextPersistence() throws Exception {
        MerchantAccess owner = createMerchant("workspace-note-secret-owner");
        MerchantAccess foreign = createMerchant("workspace-note-secret-foreign");
        ReviewFixture review = createReview(owner, "workspace-note-secret-review");
        OperatorAccess analyst = createOperator(owner, "secret-analyst", "ANALYST");
        OperatorAccess foreignManager = createOperator(foreign, "secret-foreign-manager", "RISK_MANAGER");
        assign(analyst, review.reviewCaseId(), "assign-secret-case", 0L).andExpect(status().isOk());

        for (String prohibited : List.of("card 4242 4242 4242 4242", "CVV: 123",
                "whsec_abcdefghijklmnop", "thaap_risk_0123456789abcdef0123456789abcdef",
                "https://alice:secret@example.test/path")) {
            mockMvc.perform(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                            .header(RISK_KEY, analyst.secret())
                            .header(IDEMPOTENCY_KEY, "reject-" + Math.abs(prohibited.hashCode()))
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "expectedAssignmentVersion", 1, "content", prohibited))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("review_notes")).isZero();
        mockMvc.perform(get("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                        .header(RISK_KEY, foreignManager.secret())
                        .param("expectedAssignmentVersion", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void workspaceJsonGrammarRejectsUnknownDuplicateAndUnusedFields() throws Exception {
        MerchantAccess merchant = createMerchant("workspace-grammar-owner");
        OperatorAccess viewer = createOperator(merchant, "grammar-viewer", "VIEWER");
        OperatorAccess manager = createOperator(merchant, "grammar-manager", "RISK_MANAGER");
        ReviewFixture review = createReview(merchant, "workspace-grammar-review");

        mockMvc.perform(post("/risk/workspace/reviews/search")
                        .header(RISK_KEY, viewer.secret())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sort\":\"CREATED_AT_ASC\",\"sort\":\"CREATED_AT_DESC\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/workspace/reviews/search")
                        .header(RISK_KEY, viewer.secret())
                        .contentType(APPLICATION_JSON)
                        .content("{\"expression\":\"status = OPEN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/workspace/reviews/{reviewId}/notes/{noteId}/redact",
                        review.reviewCaseId(), UUID.randomUUID())
                        .header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "closed-redaction-body")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"arbitrary\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/workspace/saved-searches")
                        .header(RISK_KEY, viewer.secret())
                        .header(IDEMPOTENCY_KEY, "null-saved-search")
                        .contentType(APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/workspace/saved-searches/{id}/disable", UUID.randomUUID())
                        .header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "null-disable")
                        .contentType(APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                        .header(RISK_KEY, manager.secret())
                        .header(IDEMPOTENCY_KEY, "null-note")
                        .contentType(APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retentionDestroysDueCiphertextOnceAndLeavesFutureNotesActive() throws Exception {
        MerchantAccess merchant = createMerchant("workspace-retention-owner");
        ReviewFixture review = createReview(merchant, "workspace-retention-review");
        OperatorAccess analyst = createOperator(merchant, "retention-analyst", "ANALYST");
        assign(analyst, review.reviewCaseId(), "assign-retention-case", 0L)
                .andExpect(status().isOk());

        JsonNode due = json(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "due-note")
                .contentType(APPLICATION_JSON)
                .content("{\"expectedAssignmentVersion\":1,\"content\":\"Due retention context\"}"), 201);
        JsonNode future = json(post("/risk/workspace/reviews/{id}/notes", review.reviewCaseId())
                .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, "future-note")
                .contentType(APPLICATION_JSON)
                .content("{\"expectedAssignmentVersion\":1,\"content\":\"Future retention context\"}"), 201);
        UUID dueId = UUID.fromString(due.get("id").asText());
        UUID futureId = UUID.fromString(future.get("id").asText());
        jdbcTemplate.update("""
                UPDATE review_notes
                   SET created_at = DATEADD('DAY', -91, CURRENT_TIMESTAMP),
                       retention_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                 WHERE id = ?
                """, dueId);

        assertThat(reviewNoteService.expireDueNotes()).isOne();
        assertThat(reviewNoteService.expireDueNotes()).isZero();
        Map<String, Object> expired = jdbcTemplate.queryForMap("""
                SELECT lifecycle_status, ciphertext, nonce, key_version, redaction_reason
                  FROM review_notes WHERE id = ?
                """, dueId);
        assertThat(expired.get("LIFECYCLE_STATUS")).isEqualTo("EXPIRED");
        assertThat(expired.get("CIPHERTEXT")).isNull();
        assertThat(expired.get("NONCE")).isNull();
        assertThat(expired.get("KEY_VERSION")).isNull();
        assertThat(expired.get("REDACTION_REASON")).isEqualTo("RETENTION_EXPIRED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM review_note_redaction_events WHERE review_note_id = ?
                """, Integer.class, dueId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM review_notes WHERE id = ?
                """, String.class, futureId)).isEqualTo("ACTIVE");
    }

    private String savedSearchBody(String name, String visibility) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", name,
                "visibility", visibility,
                "filter", Map.of("statuses", List.of("OPEN"), "slaStates", List.of("WITHIN_SLA")),
                "sort", "CREATED_AT_DESC",
                "visibleColumns", List.of("CREATED_AT", "STATUS", "SLA_STATE")));
    }

    private String updateSearchBody(long version, String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "expectedVersion", version,
                "name", name,
                "visibility", "PRIVATE",
                "filter", Map.of("statuses", List.of("OPEN")),
                "sort", "CREATED_AT_DESC",
                "visibleColumns", List.of("CREATED_AT", "STATUS")));
    }

    private void assertNoPlaintextSink(String plaintext) {
        assertThat(jdbcTemplate.queryForList(
                        "SELECT action, aggregate_type, reason, before_json, after_json FROM audit_logs")
                .toString()).doesNotContain(plaintext);
        assertThat(jdbcTemplate.queryForList("SELECT response_body FROM idempotency_records", String.class)
                .toString()).doesNotContain(plaintext);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_events", Integer.class)).isZero();
    }

    private void assertReviewNoteAccessAudit(String requestId, UUID reviewCaseId,
            int noteCount, int contentReturnedCount, String plaintext) throws Exception {
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT action, aggregate_type, aggregate_id, reason, before_json, after_json
                  FROM audit_logs
                 WHERE request_id = ? AND action = 'review_note.accessed'
                """, requestId);
        assertThat(audit.get("ACTION")).isEqualTo("review_note.accessed");
        assertThat(audit.get("AGGREGATE_TYPE")).isEqualTo("manual_review_case");
        assertThat(audit.get("AGGREGATE_ID")).isEqualTo(reviewCaseId);
        assertThat(audit.get("REASON"))
                .isEqualTo("Risk operator accessed protected review context");
        assertThat(audit.get("BEFORE_JSON")).isNull();
        JsonNode state = objectMapper.readTree(audit.get("AFTER_JSON").toString());
        assertThat(state.get("noteCount").asInt()).isEqualTo(noteCount);
        assertThat(state.get("contentReturnedCount").asInt()).isEqualTo(contentReturnedCount);
        assertThat(audit.toString()).doesNotContain(plaintext);
    }

    private Map<String, Long> domainTruthCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : new String[] {"payment_intents", "payment_attempts", "authorizations",
                "fraud_decisions", "fraud_labels", "ledger_entries", "manual_review_cases",
                "review_resolutions", "merchant_risk_profile_events"}) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            counts.put(table, count == null ? 0L : count);
        }
        return Map.copyOf(counts);
    }

    private MerchantAccess createMerchant(String name) throws Exception {
        JsonNode response = json(post("/admin/merchants")
                .header(ADMIN_TOKEN, "test-admin-bootstrap-token")
                .contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"), 201);
        return new MerchantAccess(UUID.fromString(response.get("id").asText()),
                response.get("apiKey").asText());
    }

    private OperatorAccess createOperator(MerchantAccess merchant, String key, String role) throws Exception {
        JsonNode response = json(post("/risk/operators").header(API_KEY, merchant.apiKey())
                .header(IDEMPOTENCY_KEY, key).contentType(APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}"), 201);
        return new OperatorAccess(UUID.fromString(response.get("id").asText()),
                response.get("credential").asText());
    }

    private ReviewFixture createReview(MerchantAccess merchant, String prefix) throws Exception {
        JsonNode customer = json(post("/customers").header(API_KEY, merchant.apiKey())
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + prefix + "@example.com\"}"), 201);
        UUID customerId = UUID.fromString(customer.get("id").asText());
        JsonNode method = json(post("/payment_methods").header(API_KEY, merchant.apiKey())
                .header(IDEMPOTENCY_KEY, prefix + "-method").contentType(APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId
                        + "\",\"cardNumber\":\"4999990000000003\"}"), 201);
        UUID methodId = UUID.fromString(method.get("id").asText());
        JsonNode intent = json(post("/payment_intents").header(API_KEY, merchant.apiKey())
                .header(IDEMPOTENCY_KEY, prefix + "-intent").contentType(APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId
                        + "\",\"amount\":99999,\"currency\":\"USD\",\"captureMethod\":\"MANUAL\"}"), 201);
        UUID intentId = UUID.fromString(intent.get("id").asText());
        confirm(merchant, intentId, methodId, prefix + "-confirm-1");
        confirm(merchant, intentId, methodId, prefix + "-confirm-2");
        confirm(merchant, intentId, methodId, prefix + "-confirm-3");
        UUID reviewCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM manual_review_cases WHERE payment_intent_id = ?", UUID.class, intentId);
        return new ReviewFixture(reviewCaseId);
    }

    private void confirm(MerchantAccess merchant, UUID intentId, UUID methodId, String key) throws Exception {
        mockMvc.perform(post("/payment_intents/{id}/confirm", intentId)
                        .header(API_KEY, merchant.apiKey()).header(IDEMPOTENCY_KEY, key)
                        .contentType(APPLICATION_JSON)
                        .content("{\"paymentMethodId\":\"" + methodId + "\"}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions assign(OperatorAccess analyst,
            UUID reviewCaseId, String key, long version) throws Exception {
        return mockMvc.perform(post("/risk/reviews/{id}/assign", reviewCaseId)
                .header(RISK_KEY, analyst.secret()).header(IDEMPOTENCY_KEY, key)
                .contentType(APPLICATION_JSON)
                .content("{\"expectedAssignmentVersion\":" + version + "}"));
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int statusCode) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record MerchantAccess(UUID id, String apiKey) { }
    private record OperatorAccess(UUID id, String secret) { }
    private record ReviewFixture(UUID reviewCaseId) { }
}
