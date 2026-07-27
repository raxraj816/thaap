package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V64__protect_webhook_operations_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION protect_webhook_delivery_generation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Webhook delivery projections cannot be deleted';
                        END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.webhook_endpoint_id <> OLD.webhook_endpoint_id
                           OR NEW.webhook_event_id <> OLD.webhook_event_id
                           OR NEW.event_type <> OLD.event_type OR NEW.resource_id <> OLD.resource_id
                           OR NEW.created_at <> OLD.created_at THEN
                            RAISE EXCEPTION 'Webhook delivery identity is immutable';
                        END IF;
                        IF NEW.attempt_count < OLD.attempt_count
                           OR NEW.delivery_generation < OLD.delivery_generation THEN
                            RAISE EXCEPTION 'Webhook delivery counters are monotonic';
                        END IF;
                        IF NEW.delivery_generation = OLD.delivery_generation
                           AND NEW.generation_attempt_count < OLD.generation_attempt_count THEN
                            RAISE EXCEPTION 'Webhook generation attempt count is monotonic';
                        END IF;
                        IF NEW.delivery_generation > OLD.delivery_generation THEN
                            IF NEW.delivery_generation <> OLD.delivery_generation + 1
                               OR OLD.status NOT IN ('SUCCEEDED', 'EXHAUSTED')
                               OR NEW.status <> 'PENDING'
                               OR NEW.attempt_count <> OLD.attempt_count
                               OR NEW.generation_attempt_count <> 0
                               OR NEW.next_attempt_at IS NOT NULL OR NEW.last_attempt_at IS NOT NULL
                               OR NEW.delivered_at IS NOT NULL OR NEW.last_error_code IS NOT NULL
                               OR NEW.claim_token IS NOT NULL OR NEW.lease_expires_at IS NOT NULL THEN
                                RAISE EXCEPTION 'Invalid webhook manual redelivery generation transition';
                            END IF;
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_webhook_deliveries_generation_protected
                    BEFORE UPDATE OR DELETE ON webhook_deliveries
                    FOR EACH ROW EXECUTE FUNCTION protect_webhook_delivery_generation()
                    """);
            statement.execute("""
                    CREATE FUNCTION protect_webhook_delivery_attempt_history()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Webhook delivery attempts cannot be deleted';
                        END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.webhook_delivery_id <> OLD.webhook_delivery_id
                           OR NEW.attempt_number <> OLD.attempt_number
                           OR NEW.delivery_generation <> OLD.delivery_generation
                           OR NEW.generation_attempt_number <> OLD.generation_attempt_number
                           OR NEW.dispatch_source <> OLD.dispatch_source
                           OR NEW.event_payload_sha256 <> OLD.event_payload_sha256
                           OR NEW.signing_secret_generation IS DISTINCT FROM OLD.signing_secret_generation
                           OR NEW.redelivery_request_id IS DISTINCT FROM OLD.redelivery_request_id
                           OR NEW.started_at <> OLD.started_at THEN
                            RAISE EXCEPTION 'Webhook delivery attempt identity is immutable';
                        END IF;
                        IF OLD.outcome <> 'IN_PROGRESS' THEN
                            RAISE EXCEPTION 'Terminal webhook delivery attempts are immutable';
                        END IF;
                        IF NEW.outcome = 'IN_PROGRESS'
                           AND (OLD.dispatch_started_at IS NOT NULL
                                OR NEW.dispatch_started_at IS NULL
                                OR NEW.http_request_sent IS NOT NULL) THEN
                            RAISE EXCEPTION 'Invalid webhook dispatch preparation transition';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_webhook_delivery_attempts_history_protected
                    BEFORE UPDATE OR DELETE ON webhook_delivery_attempts
                    FOR EACH ROW EXECUTE FUNCTION protect_webhook_delivery_attempt_history()
                    """);
            statement.execute("""
                    CREATE FUNCTION reject_webhook_operation_history_mutation()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Webhook operation history is append-only';
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_webhook_redelivery_requests_append_only
                    BEFORE UPDATE OR DELETE ON webhook_redelivery_requests
                    FOR EACH ROW EXECUTE FUNCTION reject_webhook_operation_history_mutation()
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_webhook_verification_events_append_only
                    BEFORE UPDATE OR DELETE ON webhook_endpoint_verification_events
                    FOR EACH ROW EXECUTE FUNCTION reject_webhook_operation_history_mutation()
                    """);
            statement.execute("""
                    CREATE FUNCTION protect_webhook_endpoint_verification()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            RAISE EXCEPTION 'Webhook endpoint verifications cannot be deleted';
                        END IF;
                        IF NEW.id <> OLD.id OR NEW.merchant_id <> OLD.merchant_id
                           OR NEW.webhook_endpoint_id <> OLD.webhook_endpoint_id
                           OR NEW.normalized_url <> OLD.normalized_url
                           OR NEW.signing_secret_generation <> OLD.signing_secret_generation
                           OR NEW.nonce_sha256 <> OLD.nonce_sha256
                           OR NEW.request_identity_hmac <> OLD.request_identity_hmac
                           OR NEW.request_identity_key_version <> OLD.request_identity_key_version
                           OR NEW.expires_at <> OLD.expires_at OR NEW.created_at <> OLD.created_at THEN
                            RAISE EXCEPTION 'Webhook endpoint verification identity is immutable';
                        END IF;
                        IF OLD.lifecycle_status <> 'PENDING' THEN
                            RAISE EXCEPTION 'Terminal webhook endpoint verification is immutable';
                        END IF;
                        IF NEW.lifecycle_status = 'PENDING'
                           AND (OLD.dispatch_started_at IS NOT NULL
                                OR NEW.dispatch_started_at IS NULL
                                OR NEW.completed_at IS NOT NULL) THEN
                            RAISE EXCEPTION 'Invalid webhook verification dispatch transition';
                        END IF;
                        RETURN NEW;
                    END; $$
                    """);
            statement.execute("""
                    CREATE TRIGGER trg_webhook_endpoint_verifications_protected
                    BEFORE UPDATE OR DELETE ON webhook_endpoint_verifications
                    FOR EACH ROW EXECUTE FUNCTION protect_webhook_endpoint_verification()
                    """);
        }
    }
}
