package com.thaap.payments.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves C55-MIG-001 on the engine that enforces it. V88–V90 must add the saved-view and bulk
 * assignment substrate without disturbing a single retained review saved search, must refuse
 * every malformed request/item shape at the database rather than in application code alone, and
 * must converge on a repeated startup.
 */
class ReviewBulkAssignmentMigrationPostgreSqlTests extends MigrationPostgreSqlTestBase {

    private static final String DIGEST = "a".repeat(64);
    private static final String IDENTITY = "b".repeat(64);

    @Test
    void freshMigrationInstallsTheBulkSubstrateItsGuardsAndItsBoundedIndexes() {
        Database database = database("bulk_fresh");
        try {
            migrate(database, null);

            assertThat(tableNames(database)).contains("review_bulk_action_requests",
                    "review_bulk_action_items", "payment_investigation_saved_views");
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(DISTINCT trigger_name) FROM information_schema.triggers
                    WHERE trigger_schema = ? AND trigger_name IN (
                      'trg_review_bulk_action_requests_protected',
                      'trg_review_bulk_action_items_protected',
                      'trg_review_bulk_action_terminal_truth')
                    """, Integer.class, database.schema())).isEqualTo(3);
            assertThat(database.jdbc().queryForList("""
                    SELECT indexname FROM pg_indexes
                    WHERE schemaname = ? AND tablename IN
                      ('review_bulk_action_requests', 'review_bulk_action_items')
                    """, String.class, database.schema()))
                    .contains("idx_review_bulk_action_requests_work",
                            "idx_review_bulk_action_items_work");
        } finally {
            drop(database);
        }
    }

    @Test
    void retainedReviewSavedSearchesSurviveV88ToV91ByteIdenticallyAndRepeatedStartupConverges() {
        Database database = database("bulk_retained");
        try {
            migrate(database, "87");
            Fixture fixture = seedLegacyWorkspace(database);

            List<Map<String, Object>> searchesBefore = database.jdbc().queryForList(
                    "SELECT * FROM " + database.schema() + ".review_saved_searches ORDER BY id");
            assertThat(searchesBefore).as("the retained-preservation claim is vacuous without a "
                    + "legacy saved search to preserve").isNotEmpty();

            migrate(database, null);

            assertThat(database.jdbc().queryForList(
                    "SELECT * FROM " + database.schema() + ".review_saved_searches ORDER BY id"))
                    .as("V88 must not rewrite, convert, or migrate an existing review saved search")
                    .isEqualTo(searchesBefore);
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM %s.payment_investigation_saved_views
                    """.formatted(database.schema()), Integer.class))
                    .as("the new saved-view table starts empty; no legacy row is copied into it")
                    .isZero();
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM information_schema.triggers
                    WHERE trigger_schema = ? AND event_object_table = 'review_saved_searches'
                      AND trigger_name LIKE '%%bulk%%'
                    """, Integer.class, database.schema()))
                    .as("no bulk-assignment guard may attach itself to the legacy saved-search table")
                    .isZero();

            // Repeated startup on an already-migrated schema converges rather than reapplying.
            migrate(database, null);
            assertThat(database.jdbc().queryForObject("""
                    SELECT MAX(version::numeric) FROM %s.flyway_schema_history WHERE success = TRUE
                    """.formatted(database.schema()), Integer.class)).isEqualTo(91);
            assertThat(database.jdbc().queryForList(
                    "SELECT * FROM " + database.schema() + ".review_saved_searches ORDER BY id"))
                    .isEqualTo(searchesBefore);
            assertThat(fixture.operatorId()).isNotNull();
        } finally {
            drop(database);
        }
    }

    @Test
    void everyMalformedBulkRequestShapeIsRefusedByTheDatabaseNotOnlyByApplicationCode() {
        Database database = database("bulk_request_shapes");
        try {
            migrate(database, null);
            Fixture fixture = seedLegacyWorkspace(database);

            // ASSIGN_TO_OPERATOR without a target, and a non-targeted action carrying one.
            assertThatThrownBy(() -> insertRequest(database, fixture, UUID.randomUUID(),
                    "ASSIGN_TO_OPERATOR", null, 1, "PENDING", 0, 0))
                    .hasMessageContaining("ck_review_bulk_action_requests_target");
            assertThatThrownBy(() -> insertRequest(database, fixture, UUID.randomUUID(),
                    "ASSIGN_TO_SELF", fixture.operatorId(), 1, "PENDING", 0, 0))
                    .hasMessageContaining("ck_review_bulk_action_requests_target");

            // An action outside the closed three-operation vocabulary.
            assertThatThrownBy(() -> insertRequest(database, fixture, UUID.randomUUID(),
                    "RESOLVE", null, 1, "PENDING", 0, 0))
                    .hasMessageContaining("ck_review_bulk_action_requests_action");

            // 0 and 26 items bracket the frozen 1..25 bound.
            assertThatThrownBy(() -> insertRequest(database, fixture, UUID.randomUUID(),
                    "ASSIGN_TO_SELF", null, 0, "PENDING", 0, 0))
                    .hasMessageContaining("ck_review_bulk_action_requests_count");
            assertThatThrownBy(() -> insertRequest(database, fixture, UUID.randomUUID(),
                    "ASSIGN_TO_SELF", null, 26, "PENDING", 0, 0))
                    .hasMessageContaining("ck_review_bulk_action_requests_count");

            // Counters that exceed the item count, and an unknown lifecycle status.
            assertThatThrownBy(() -> insertRequest(database, fixture, UUID.randomUUID(),
                    "ASSIGN_TO_SELF", null, 2, "PENDING", 2, 1))
                    .hasMessageContaining("ck_review_bulk_action_requests_count");
            assertThatThrownBy(() -> insertRequest(database, fixture, UUID.randomUUID(),
                    "ASSIGN_TO_SELF", null, 1, "SORT_OF_DONE", 0, 0))
                    .hasMessageContaining("ck_review_bulk_action_requests_status");

            // A short digest cannot masquerade as a SHA-256 identity.
            assertThatThrownBy(() -> database.jdbc().update("""
                    INSERT INTO %s.review_bulk_action_requests
                      (id, merchant_id, initiating_operator_id, action_code, target_operator_id,
                       idempotency_identity_hmac, request_digest, item_count, lifecycle_status,
                       succeeded_count, failed_count, lease_epoch, created_at, updated_at)
                    VALUES (?, ?, ?, 'ASSIGN_TO_SELF', NULL, 'short', ?, 1, 'PENDING', 0, 0, 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), UUID.randomUUID(), fixture.merchantId(),
                    fixture.operatorId(), DIGEST))
                    .hasMessageContaining("ck_review_bulk_action_requests_digest");

            // A lease token without an expiry is an unfenceable claim.
            assertThatThrownBy(() -> database.jdbc().update("""
                    INSERT INTO %s.review_bulk_action_requests
                      (id, merchant_id, initiating_operator_id, action_code, target_operator_id,
                       idempotency_identity_hmac, request_digest, item_count, lifecycle_status,
                       succeeded_count, failed_count, lease_token, lease_epoch,
                       created_at, updated_at)
                    VALUES (?, ?, ?, 'ASSIGN_TO_SELF', NULL, ?, ?, 1, 'RUNNING', 0, 0, ?, 1,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), UUID.randomUUID(), fixture.merchantId(),
                    fixture.operatorId(), IDENTITY, DIGEST, UUID.randomUUID()))
                    .hasMessageContaining("ck_review_bulk_action_requests_lease");

            // An initiating operator from another tenant cannot own a request.
            Fixture neighbour = seedLegacyWorkspace(database);
            assertThatThrownBy(() -> database.jdbc().update("""
                    INSERT INTO %s.review_bulk_action_requests
                      (id, merchant_id, initiating_operator_id, action_code, target_operator_id,
                       idempotency_identity_hmac, request_digest, item_count, lifecycle_status,
                       succeeded_count, failed_count, lease_epoch, created_at, updated_at)
                    VALUES (?, ?, ?, 'ASSIGN_TO_SELF', NULL, ?, ?, 1, 'PENDING', 0, 0, 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), UUID.randomUUID(), fixture.merchantId(),
                    neighbour.operatorId(), IDENTITY, DIGEST))
                    .hasMessageContaining("fk_review_bulk_action_requests_actor");

            // One principal cannot hold two requests under one idempotency identity.
            insertRequest(database, fixture, UUID.randomUUID(), "ASSIGN_TO_SELF", null, 1,
                    "PENDING", 0, 0);
            assertThatThrownBy(() -> insertRequest(database, fixture, UUID.randomUUID(),
                    "ASSIGN_TO_SELF", null, 1, "PENDING", 0, 0))
                    .hasMessageContaining("uk_review_bulk_action_requests_idempotency");
        } finally {
            drop(database);
        }
    }

    @Test
    void everyMalformedBulkItemShapeIsRefusedByTheDatabaseNotOnlyByApplicationCode() {
        Database database = database("bulk_item_shapes");
        try {
            migrate(database, null);
            Fixture fixture = seedLegacyWorkspace(database);
            UUID requestId = UUID.randomUUID();
            insertRequest(database, fixture, requestId, "ASSIGN_TO_SELF", null, 2, "PENDING", 0, 0);
            UUID reviewCaseId = UUID.randomUUID();

            // Ordinal 0 and 26 bracket the frozen 1..25 bound.
            assertThatThrownBy(() -> insertItem(database, fixture, requestId, UUID.randomUUID(),
                    reviewCaseId, 0, 0, "PENDING"))
                    .hasMessageContaining("ck_review_bulk_action_items_ordinal");
            assertThatThrownBy(() -> insertItem(database, fixture, requestId, UUID.randomUUID(),
                    reviewCaseId, 26, 0, "PENDING"))
                    .hasMessageContaining("ck_review_bulk_action_items_ordinal");

            // A negative expected version cannot exist.
            assertThatThrownBy(() -> insertItem(database, fixture, requestId, UUID.randomUUID(),
                    reviewCaseId, 1, -1, "PENDING"))
                    .hasMessageContaining("ck_review_bulk_action_items_version");

            // A PENDING item carrying a completion, and a terminal item missing one, are both
            // impossible shapes: the item status and its evidence must agree.
            assertThatThrownBy(() -> database.jdbc().update("""
                    INSERT INTO %s.review_bulk_action_items
                      (id, merchant_id, bulk_action_request_id, manual_review_case_id,
                       expected_assignment_version, ordinal, lifecycle_status, failure_code,
                       completed_at, created_at)
                    VALUES (?, ?, ?, ?, 0, 1, 'PENDING', 'why_is_this_here', CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), UUID.randomUUID(), fixture.merchantId(),
                    requestId, reviewCaseId))
                    .hasMessageContaining("ck_review_bulk_action_items_failure");
            assertThatThrownBy(() -> database.jdbc().update("""
                    INSERT INTO %s.review_bulk_action_items
                      (id, merchant_id, bulk_action_request_id, manual_review_case_id,
                       expected_assignment_version, ordinal, lifecycle_status, created_at)
                    VALUES (?, ?, ?, ?, 0, 1, 'SUCCEEDED', CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), UUID.randomUUID(), fixture.merchantId(),
                    requestId, reviewCaseId))
                    .hasMessageContaining("ck_review_bulk_action_items_failure");

            // A CLAIMED item without its fence is unrecoverable, so it cannot be written.
            assertThatThrownBy(() -> insertItem(database, fixture, requestId, UUID.randomUUID(),
                    reviewCaseId, 1, 0, "CLAIMED"))
                    .hasMessageContaining("ck_review_bulk_action_items_claim");

            insertItem(database, fixture, requestId, UUID.randomUUID(), reviewCaseId, 1, 0,
                    "PENDING");

            // Neither the ordinal nor the review case may repeat inside one request.
            assertThatThrownBy(() -> insertItem(database, fixture, requestId, UUID.randomUUID(),
                    UUID.randomUUID(), 1, 0, "PENDING"))
                    .hasMessageContaining("uk_review_bulk_action_items_ordinal");
            assertThatThrownBy(() -> insertItem(database, fixture, requestId, UUID.randomUUID(),
                    reviewCaseId, 2, 0, "PENDING"))
                    .hasMessageContaining("uk_review_bulk_action_items_review");
        } finally {
            drop(database);
        }
    }

    /**
     * The item table deliberately carries no foreign key to {@code manual_review_cases}: resolving
     * a review at admission time would itself disclose whether that review exists in another
     * tenant. The tenant boundary is instead a tenant-qualified foreign key to the owning request,
     * so an item always belongs to exactly one tenant's request, and the mutation boundary
     * terminates an unknown or foreign review as a non-disclosing per-item outcome.
     */
    @Test
    void anItemIsTenantBoundThroughItsRequestRatherThanByResolvingTheReviewAtAdmission() {
        Database database = database("bulk_item_tenant_binding");
        try {
            migrate(database, null);
            Fixture fixture = seedLegacyWorkspace(database);
            Fixture neighbour = seedLegacyWorkspace(database);
            UUID requestId = UUID.randomUUID();
            insertRequest(database, fixture, requestId, "ASSIGN_TO_SELF", null, 2, "PENDING", 0, 0);

            // Admission stays permissive about the review's existence, by design.
            insertItem(database, fixture, requestId, UUID.randomUUID(), UUID.randomUUID(), 1, 0,
                    "PENDING");

            // But the item can never attach itself to another tenant's request, nor claim a
            // merchant its request does not belong to.
            assertThatThrownBy(() -> insertItem(database, fixture, requestId, UUID.randomUUID(),
                    UUID.randomUUID(), 2, 0, "PENDING", neighbour.merchantId()))
                    .hasMessageContaining("fk_review_bulk_action_items_request");
            assertThatThrownBy(() -> insertItem(database, fixture, UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), 2, 0, "PENDING"))
                    .hasMessageContaining("fk_review_bulk_action_items_request");

            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM %s.review_bulk_action_items WHERE merchant_id = ?
                    """.formatted(database.schema()), Integer.class, neighbour.merchantId()))
                    .isZero();
        } finally {
            drop(database);
        }
    }

    @Test
    void terminalRequestAndItemHistoryCannotBeRewrittenOrDeleted() {
        Database database = database("bulk_history_guards");
        try {
            migrate(database, null);
            Fixture fixture = seedLegacyWorkspace(database);
            UUID requestId = UUID.randomUUID();
            insertRequest(database, fixture, requestId, "ASSIGN_TO_SELF", null, 1, "PENDING", 0, 0);
            UUID itemId = UUID.randomUUID();
            insertItem(database, fixture, requestId, itemId, UUID.randomUUID(), 1, 0, "PENDING");

            // Immutable request identity fields are frozen at insert.
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.review_bulk_action_requests SET action_code = 'RELEASE' WHERE id = ?
                    """.formatted(database.schema()), requestId))
                    .hasMessageContaining("immutable history was changed");
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.review_bulk_action_requests SET item_count = 25 WHERE id = ?
                    """.formatted(database.schema()), requestId))
                    .hasMessageContaining("immutable history was changed");

            // A fence may only move forward. The BEFORE UPDATE guard fires ahead of the
            // ck_review_bulk_action_requests_epoch check, so the trigger is what refuses here;
            // the check constraint remains the backstop for a direct insert.
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.review_bulk_action_requests SET lease_epoch = -1 WHERE id = ?
                    """.formatted(database.schema()), requestId))
                    .hasMessageContaining("counters or fence moved backwards");
            assertThatThrownBy(() -> database.jdbc().update("""
                    INSERT INTO %s.review_bulk_action_requests
                      (id, merchant_id, initiating_operator_id, action_code, target_operator_id,
                       idempotency_identity_hmac, request_digest, item_count, lifecycle_status,
                       succeeded_count, failed_count, lease_epoch, created_at, updated_at)
                    VALUES (?, ?, ?, 'ASSIGN_TO_SELF', NULL, ?, ?, 1, 'PENDING', 0, 0, -1,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(database.schema()), UUID.randomUUID(), fixture.merchantId(),
                    fixture.operatorId(), "c".repeat(64), DIGEST))
                    .hasMessageContaining("ck_review_bulk_action_requests_epoch");

            // History is never deleted, at either level.
            assertThatThrownBy(() -> database.jdbc().update("""
                    DELETE FROM %s.review_bulk_action_items WHERE id = ?
                    """.formatted(database.schema()), itemId))
                    .hasMessageContaining("cannot be deleted");
            assertThatThrownBy(() -> database.jdbc().update("""
                    DELETE FROM %s.review_bulk_action_requests WHERE id = ?
                    """.formatted(database.schema()), requestId))
                    .hasMessageContaining("cannot be deleted");

            // A terminal item never returns to pending and never has its outcome edited.
            database.jdbc().update("""
                    UPDATE %s.review_bulk_action_items
                    SET lifecycle_status = 'STALE', failure_code = 'assignment_version_stale',
                        completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.formatted(database.schema()), itemId);
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.review_bulk_action_items SET lifecycle_status = 'PENDING',
                        failure_code = NULL, completed_at = NULL WHERE id = ?
                    """.formatted(database.schema()), itemId))
                    .hasMessageContaining("immutable history was changed");
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.review_bulk_action_items SET failure_code = 'looks_nicer' WHERE id = ?
                    """.formatted(database.schema()), itemId))
                    .hasMessageContaining("immutable history was changed");
        } finally {
            drop(database);
        }
    }

    @Test
    void aTerminalRequestStatusThatDisagreesWithItsItemTruthIsRefused() {
        Database database = database("bulk_terminal_truth");
        try {
            migrate(database, null);
            Fixture fixture = seedLegacyWorkspace(database);
            UUID requestId = UUID.randomUUID();
            insertRequest(database, fixture, requestId, "ASSIGN_TO_SELF", null, 1, "PENDING", 0, 0);
            UUID itemId = UUID.randomUUID();
            insertItem(database, fixture, requestId, itemId, UUID.randomUUID(), 1, 0, "PENDING");
            database.jdbc().update("""
                    UPDATE %s.review_bulk_action_items
                    SET lifecycle_status = 'STALE', failure_code = 'assignment_version_stale',
                        completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.formatted(database.schema()), itemId);

            // The single item failed, so COMPLETED is a lie the constraint trigger must reject.
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.review_bulk_action_requests
                    SET lifecycle_status = 'COMPLETED', succeeded_count = 1, failed_count = 0,
                        completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.formatted(database.schema()), requestId))
                    .hasMessageContaining("does not match item truth");

            // PARTIAL is equally a lie when nothing succeeded.
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.review_bulk_action_requests
                    SET lifecycle_status = 'PARTIAL', succeeded_count = 0, failed_count = 1,
                        completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.formatted(database.schema()), requestId))
                    .hasMessageContaining("does not match item truth");

            // FAILED with the honest counters is accepted.
            database.jdbc().update("""
                    UPDATE %s.review_bulk_action_requests
                    SET lifecycle_status = 'FAILED', succeeded_count = 0, failed_count = 1,
                        completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """.formatted(database.schema()), requestId);
            assertThat(database.jdbc().queryForObject("""
                    SELECT lifecycle_status FROM %s.review_bulk_action_requests WHERE id = ?
                    """.formatted(database.schema()), String.class, requestId)).isEqualTo("FAILED");

            // Once terminal, the request is closed to further edits.
            assertThatThrownBy(() -> database.jdbc().update("""
                    UPDATE %s.review_bulk_action_requests SET lifecycle_status = 'RUNNING'
                    WHERE id = ?
                    """.formatted(database.schema()), requestId))
                    .hasMessageContaining("immutable history was changed");
        } finally {
            drop(database);
        }
    }

    private Fixture seedLegacyWorkspace(Database database) {
        String schema = database.schema();
        UUID merchantId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();
        database.jdbc().update("""
                INSERT INTO %s.merchants (id, name, status, api_key_prefix, api_key_hash, created_at)
                VALUES (?, ?, 'ACTIVE', 'thaap_', ?, CURRENT_TIMESTAMP)
                """.formatted(schema), merchantId, "bulk-migration-" + merchantId,
                "hash-" + merchantId);
        database.jdbc().update("""
                INSERT INTO %s.risk_operators (id, merchant_id, operator_code, role, status,
                    lifecycle_epoch, created_by_api_key_prefix, created_at)
                VALUES (?, ?, ?, 'RISK_MANAGER', 'ACTIVE', 1, 'thaap_', CURRENT_TIMESTAMP)
                """.formatted(schema), operatorId, merchantId, "op-" + operatorId);
        database.jdbc().update("""
                INSERT INTO %s.review_saved_searches (id, merchant_id, owner_operator_id, name,
                    visibility, filter_schema_version, canonical_filter_json, sort_code,
                    visible_columns_json, definition_digest, lifecycle_status, lifecycle_version,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PRIVATE', 'review_queue_filter_v1', '{}', 'CREATED_AT_DESC',
                        '[]', ?, 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(schema), UUID.randomUUID(), merchantId, operatorId,
                "legacy-search-" + operatorId, DIGEST);
        return new Fixture(merchantId, operatorId);
    }

    private void insertRequest(Database database, Fixture fixture, UUID requestId, String action,
            UUID target, int itemCount, String status, int succeeded, int failed) {
        database.jdbc().update("""
                INSERT INTO %s.review_bulk_action_requests
                  (id, merchant_id, initiating_operator_id, action_code, target_operator_id,
                   idempotency_identity_hmac, request_digest, item_count, lifecycle_status,
                   succeeded_count, failed_count, lease_epoch, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(database.schema()), requestId, fixture.merchantId(),
                fixture.operatorId(), action, target, IDENTITY, DIGEST, itemCount, status,
                succeeded, failed);
    }

    private void insertItem(Database database, Fixture fixture, UUID requestId, UUID itemId,
            UUID reviewCaseId, int ordinal, long expectedVersion, String status) {
        insertItem(database, fixture, requestId, itemId, reviewCaseId, ordinal, expectedVersion,
                status, fixture.merchantId());
    }

    private void insertItem(Database database, Fixture fixture, UUID requestId, UUID itemId,
            UUID reviewCaseId, int ordinal, long expectedVersion, String status, UUID merchantId) {
        database.jdbc().update("""
                INSERT INTO %s.review_bulk_action_items
                  (id, merchant_id, bulk_action_request_id, manual_review_case_id,
                   expected_assignment_version, ordinal, lifecycle_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """.formatted(database.schema()), itemId, merchantId, requestId, reviewCaseId,
                expectedVersion, ordinal, status);
    }

    private List<String> tableNames(Database database) {
        return database.jdbc().queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = ?
                """, String.class, database.schema());
    }


    private record Fixture(UUID merchantId, UUID operatorId) {
    }
}
