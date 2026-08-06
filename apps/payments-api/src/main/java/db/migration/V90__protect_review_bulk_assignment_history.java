package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V90__protect_review_bulk_assignment_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION protect_review_bulk_action_request()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Bulk review request history cannot be deleted';
                        END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.initiating_operator_id <> OLD.initiating_operator_id
                           OR NEW.action_code <> OLD.action_code
                           OR NEW.target_operator_id IS DISTINCT FROM OLD.target_operator_id
                           OR NEW.idempotency_identity_hmac <> OLD.idempotency_identity_hmac
                           OR NEW.request_digest <> OLD.request_digest
                           OR NEW.item_count <> OLD.item_count OR NEW.created_at <> OLD.created_at
                           OR OLD.lifecycle_status IN ('COMPLETED', 'PARTIAL', 'FAILED') THEN
                            RAISE EXCEPTION 'Bulk review request immutable history was changed';
                        END IF;
                        IF NEW.lease_epoch < OLD.lease_epoch
                           OR NEW.succeeded_count < OLD.succeeded_count
                           OR NEW.failed_count < OLD.failed_count THEN
                            RAISE EXCEPTION 'Bulk review request counters or fence moved backwards';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_review_bulk_action_requests_protected
                    BEFORE UPDATE OR DELETE ON review_bulk_action_requests
                    FOR EACH ROW EXECUTE FUNCTION protect_review_bulk_action_request()
                    """);
            statement.execute("""
                    CREATE FUNCTION protect_review_bulk_action_item()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Bulk review item history cannot be deleted';
                        END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.bulk_action_request_id <> OLD.bulk_action_request_id
                           OR NEW.manual_review_case_id <> OLD.manual_review_case_id
                           OR NEW.expected_assignment_version <> OLD.expected_assignment_version
                           OR NEW.ordinal <> OLD.ordinal OR NEW.created_at <> OLD.created_at
                           OR OLD.lifecycle_status IN
                              ('SUCCEEDED', 'STALE', 'NOT_ELIGIBLE', 'NOT_FOUND', 'DENIED', 'FAILED') THEN
                            RAISE EXCEPTION 'Bulk review item immutable history was changed';
                        END IF;
                        IF OLD.lifecycle_status = 'PENDING'
                           AND NEW.lifecycle_status NOT IN
                              ('CLAIMED', 'STALE', 'NOT_ELIGIBLE', 'NOT_FOUND', 'DENIED', 'FAILED') THEN
                            RAISE EXCEPTION 'Bulk review item transition is invalid';
                        END IF;
                        IF OLD.lifecycle_status = 'CLAIMED'
                           AND NEW.lifecycle_status NOT IN
                              ('CLAIMED', 'SUCCEEDED', 'STALE', 'NOT_ELIGIBLE',
                               'NOT_FOUND', 'DENIED', 'FAILED') THEN
                            RAISE EXCEPTION 'Claimed bulk review item transition is invalid';
                        END IF;
                        IF OLD.claim_lease_epoch IS NOT NULL AND NEW.claim_lease_epoch IS NOT NULL
                           AND NEW.claim_lease_epoch < OLD.claim_lease_epoch THEN
                            RAISE EXCEPTION 'Bulk review item fence moved backwards';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_review_bulk_action_items_protected
                    BEFORE UPDATE OR DELETE ON review_bulk_action_items
                    FOR EACH ROW EXECUTE FUNCTION protect_review_bulk_action_item()
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_terminal_review_bulk_action_request()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE succeeded integer;
                    DECLARE failed integer;
                    DECLARE terminal integer;
                    BEGIN
                        IF NEW.lifecycle_status IN ('COMPLETED', 'PARTIAL', 'FAILED') THEN
                            SELECT COUNT(*) FILTER (WHERE lifecycle_status = 'SUCCEEDED'),
                                   COUNT(*) FILTER (WHERE lifecycle_status IN
                                       ('STALE', 'NOT_ELIGIBLE', 'NOT_FOUND', 'DENIED', 'FAILED')),
                                   COUNT(*) FILTER (WHERE lifecycle_status IN
                                       ('SUCCEEDED', 'STALE', 'NOT_ELIGIBLE', 'NOT_FOUND', 'DENIED', 'FAILED'))
                              INTO succeeded, failed, terminal
                              FROM review_bulk_action_items
                             WHERE merchant_id = NEW.merchant_id
                               AND bulk_action_request_id = NEW.id;
                            IF terminal <> NEW.item_count OR succeeded <> NEW.succeeded_count
                               OR failed <> NEW.failed_count
                               OR (NEW.lifecycle_status = 'COMPLETED' AND failed <> 0)
                               OR (NEW.lifecycle_status = 'FAILED' AND succeeded <> 0)
                               OR (NEW.lifecycle_status = 'PARTIAL'
                                   AND (succeeded = 0 OR failed = 0)) THEN
                                RAISE EXCEPTION 'Bulk review terminal request does not match item truth';
                            END IF;
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE CONSTRAINT TRIGGER trg_review_bulk_action_terminal_truth
                    AFTER INSERT OR UPDATE ON review_bulk_action_requests
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION validate_terminal_review_bulk_action_request()
                    """);
        }
    }
}

