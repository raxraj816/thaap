package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Installs PostgreSQL history guards while keeping the H2 regression profile migratable. */
public final class V66__protect_domain_event_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE domain_events
                    ADD CONSTRAINT ck_domain_events_payload_sha256_lower_hex
                    CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
                    """);
            statement.execute("""
                    ALTER TABLE domain_events
                    ADD CONSTRAINT ck_domain_events_record_sha256_lower_hex
                    CHECK (event_record_sha256 ~ '^[0-9a-f]{64}$')
                    """);
            statement.execute("""
                    ALTER TABLE platform_operator_credentials
                    ADD CONSTRAINT ck_platform_operator_credentials_lower_hex
                    CHECK (secret_sha256 ~ '^[0-9a-f]{64}$'
                       AND secret_identity_hmac ~ '^[0-9a-f]{64}$')
                    """);
            statement.execute("""
                    ALTER TABLE platform_operator_requests
                    ADD CONSTRAINT ck_platform_operator_requests_lower_hex
                    CHECK (request_identity_hmac ~ '^[0-9a-f]{64}$'
                       AND request_digest ~ '^[0-9a-f]{64}$')
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION reject_domain_event_history_mutation()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        RAISE EXCEPTION 'domain event history is immutable'
                            USING ERRCODE = '55000';
                    END;
                    $function$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_domain_events_immutable
                    BEFORE UPDATE OR DELETE ON domain_events
                    FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION reject_event_stream_head_regression()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        IF NEW.merchant_id <> OLD.merchant_id
                           OR NEW.stream_type <> OLD.stream_type
                           OR NEW.stream_id <> OLD.stream_id
                           OR NEW.current_version < OLD.current_version THEN
                            RAISE EXCEPTION 'event stream head identity and version cannot regress'
                                USING ERRCODE = '55000';
                        END IF;
                        RETURN NEW;
                    END;
                    $function$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_event_stream_heads_no_regression
                    BEFORE UPDATE ON event_stream_heads
                    FOR EACH ROW EXECUTE FUNCTION reject_event_stream_head_regression()
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION protect_event_publication_identity()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'event publication evidence cannot be deleted'
                                USING ERRCODE = '55000';
                        END IF;
                        IF NEW.id <> OLD.id
                           OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.domain_event_id <> OLD.domain_event_id
                           OR NEW.created_at <> OLD.created_at THEN
                            RAISE EXCEPTION 'event publication identity is immutable'
                                USING ERRCODE = '55000';
                        END IF;
                        RETURN NEW;
                    END;
                    $function$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_event_publications_identity_immutable
                    BEFORE UPDATE OR DELETE ON event_publications
                    FOR EACH ROW EXECUTE FUNCTION protect_event_publication_identity()
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_platform_operator_requests_immutable
                    BEFORE UPDATE OR DELETE ON platform_operator_requests
                    FOR EACH ROW EXECUTE FUNCTION reject_domain_event_history_mutation()
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION protect_platform_operator_lifecycle()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'platform operator history cannot be deleted'
                                USING ERRCODE = '55000';
                        END IF;
                        IF NEW.id <> OLD.id
                           OR NEW.operator_code <> OLD.operator_code
                           OR NEW.display_name <> OLD.display_name
                           OR NEW.role <> OLD.role
                           OR NEW.created_by_operator_id IS DISTINCT FROM OLD.created_by_operator_id
                           OR NEW.created_at <> OLD.created_at
                           OR (OLD.status = 'DISABLED' AND NEW.status <> 'DISABLED')
                           OR (OLD.status = 'ACTIVE' AND NEW.status NOT IN ('ACTIVE', 'DISABLED'))
                           OR NEW.lifecycle_epoch < OLD.lifecycle_epoch
                           OR (NEW.status = 'DISABLED' AND NEW.lifecycle_epoch <= OLD.lifecycle_epoch)
                           OR (NEW.status = OLD.status AND NEW.lifecycle_epoch <> OLD.lifecycle_epoch)
                           OR (OLD.disabled_at IS NOT NULL AND NEW.disabled_at IS DISTINCT FROM OLD.disabled_at) THEN
                            RAISE EXCEPTION 'platform operator lifecycle mutation is invalid'
                                USING ERRCODE = '55000';
                        END IF;
                        RETURN NEW;
                    END;
                    $function$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_platform_operators_lifecycle_protected
                    BEFORE UPDATE OR DELETE ON platform_operators
                    FOR EACH ROW EXECUTE FUNCTION protect_platform_operator_lifecycle()
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION protect_platform_credential_lifecycle()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'platform credential history cannot be deleted'
                                USING ERRCODE = '55000';
                        END IF;
                        IF NEW.id <> OLD.id
                           OR NEW.platform_operator_id <> OLD.platform_operator_id
                           OR NEW.secret_prefix <> OLD.secret_prefix
                           OR NEW.secret_sha256 <> OLD.secret_sha256
                           OR NEW.secret_identity_hmac <> OLD.secret_identity_hmac
                           OR NEW.secret_hmac_key_version <> OLD.secret_hmac_key_version
                           OR NEW.created_at <> OLD.created_at
                           OR (OLD.disabled_at IS NOT NULL AND NEW.disabled_at IS DISTINCT FROM OLD.disabled_at) THEN
                            RAISE EXCEPTION 'platform credential lifecycle mutation is invalid'
                                USING ERRCODE = '55000';
                        END IF;
                        RETURN NEW;
                    END;
                    $function$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_platform_operator_credentials_lifecycle_protected
                    BEFORE UPDATE OR DELETE ON platform_operator_credentials
                    FOR EACH ROW EXECUTE FUNCTION protect_platform_credential_lifecycle()
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION protect_platform_bootstrap_state()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $function$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'platform bootstrap state cannot be deleted'
                                USING ERRCODE = '55000';
                        END IF;
                        IF NEW.singleton_id <> OLD.singleton_id
                           OR (OLD.bootstrap_completed AND NOT NEW.bootstrap_completed)
                           OR (OLD.first_admin_id IS NOT NULL
                               AND NEW.first_admin_id IS DISTINCT FROM OLD.first_admin_id) THEN
                            RAISE EXCEPTION 'platform bootstrap state cannot regress'
                                USING ERRCODE = '55000';
                        END IF;
                        RETURN NEW;
                    END;
                    $function$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_platform_operator_bootstrap_state_protected
                    BEFORE UPDATE OR DELETE ON platform_operator_bootstrap_state
                    FOR EACH ROW EXECUTE FUNCTION protect_platform_bootstrap_state()
                    """);
        }
    }
}
