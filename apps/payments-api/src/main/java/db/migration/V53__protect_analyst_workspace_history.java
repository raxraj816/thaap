package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V53__protect_analyst_workspace_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION reject_review_note_history_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Review note history cannot be deleted';
                        END IF;
                        IF OLD.lifecycle_status <> 'ACTIVE'
                           OR NEW.lifecycle_status NOT IN ('REDACTED', 'EXPIRED')
                           OR NEW.id <> OLD.id
                           OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.manual_review_case_id <> OLD.manual_review_case_id
                           OR NEW.author_operator_id <> OLD.author_operator_id
                           OR NEW.author_assignment_version <> OLD.author_assignment_version
                           OR NEW.supersedes_note_id IS DISTINCT FROM OLD.supersedes_note_id
                           OR NEW.plaintext_code_points <> OLD.plaintext_code_points
                           OR NEW.created_at <> OLD.created_at
                           OR NEW.retention_expires_at <> OLD.retention_expires_at
                           OR NEW.ciphertext IS NOT NULL
                           OR NEW.nonce IS NOT NULL
                           OR NEW.key_version IS NOT NULL THEN
                            RAISE EXCEPTION 'Review note history permits only terminal ciphertext destruction';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_review_notes_protected_history
                    BEFORE UPDATE OR DELETE ON review_notes
                    FOR EACH ROW EXECUTE FUNCTION reject_review_note_history_mutation()
                    """);
            statement.execute("""
                    CREATE FUNCTION reject_review_note_redaction_event_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Review note redaction events are append-only';
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_review_note_redaction_events_append_only
                    BEFORE UPDATE OR DELETE ON review_note_redaction_events
                    FOR EACH ROW EXECUTE FUNCTION reject_review_note_redaction_event_mutation()
                    """);
            statement.execute("""
                    CREATE FUNCTION validate_review_note_redaction_event()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    DECLARE note_row review_notes%ROWTYPE;
                    BEGIN
                        SELECT * INTO note_row FROM review_notes
                         WHERE merchant_id = NEW.merchant_id AND id = NEW.review_note_id;
                        IF NOT FOUND
                           OR note_row.manual_review_case_id <> NEW.manual_review_case_id
                           OR note_row.lifecycle_status = 'ACTIVE'
                           OR note_row.redaction_reason <> NEW.event_type
                           OR note_row.redacted_at <> NEW.created_at
                           OR note_row.redacted_by_operator_id IS DISTINCT FROM NEW.actor_operator_id THEN
                            RAISE EXCEPTION 'Review note redaction event must match terminal note state';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE CONSTRAINT TRIGGER trg_review_note_redaction_event_semantics
                    AFTER INSERT ON review_note_redaction_events
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION validate_review_note_redaction_event()
                    """);
        }
    }
}
