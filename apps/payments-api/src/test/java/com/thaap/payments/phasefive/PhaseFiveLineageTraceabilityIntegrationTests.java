package com.thaap.payments.phasefive;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaap.payments.projection.rebuild.ProjectionRebuildControlService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proof for {@code C52-DATA-001}.
 *
 * <p>A merchant-visible result must trace back to canonical bytes. This walks the whole chain for
 * a representative payment in each tenant: projection row to active generation, generation to
 * source cutoff manifest and handler version, stream position to the exact domain event, and the
 * event to its own record digest. A chain that breaks anywhere means a number on a dashboard
 * nobody can defend.
 */
class PhaseFiveLineageTraceabilityIntegrationTests extends PhaseFiveClosureFixture {

    @Test
    void everyProjectedRowTracesToCanonicalEventBytesThroughItsActiveGeneration() {
        ProjectionRebuildControlService.RebuildView rebuild =
                runRebuildToActivation("closure-lineage");

        for (Tenant tenant : java.util.List.of(first, second)) {
            Map<String, Object> lineage = jdbcTemplate.queryForMap("""
                    SELECT position.last_domain_event_id AS event_id,
                           position.last_event_record_sha256 AS position_digest,
                           event.event_record_sha256 AS event_digest,
                           event.stream_version AS event_version,
                           position.last_stream_version AS position_version,
                           generation.handler_version AS handler_version,
                           manifest.canonical_manifest_sha256 AS cutoff_digest,
                           generation.source_cutoff_digest AS generation_digest
                      FROM projection_stream_positions position
                      JOIN domain_events event
                        ON event.id = position.last_domain_event_id
                       AND event.merchant_id = position.merchant_id
                      JOIN projection_generations generation
                        ON generation.id = position.projection_generation_id
                      JOIN projection_rebuilds rebuild
                        ON rebuild.projection_generation_id = generation.id
                      JOIN projection_cutoff_manifests manifest
                        ON manifest.id = rebuild.cutoff_manifest_id
                     WHERE position.projection_generation_id = ?
                       AND position.merchant_id = ?
                       AND position.stream_id = ?
                    """, rebuild.generationId(), tenant.merchantId(), tenant.paymentIntentId());

            // The projection's record of the event matches the event's own bytes, so the row is
            // attributable to a specific canonical record rather than to a version number.
            assertThat(lineage.get("position_digest"))
                    .describedAs("%s projection position disagrees with the event it names",
                            tenant.key())
                    .isEqualTo(lineage.get("event_digest"));
            assertThat(lineage.get("position_version")).isEqualTo(lineage.get("event_version"));
            assertThat((String) lineage.get("event_digest")).hasSize(64);
            // The generation is bound to the cutoff manifest it was built from.
            assertThat(lineage.get("generation_digest")).isEqualTo(lineage.get("cutoff_digest"));
            assertThat(lineage.get("handler_version")).isEqualTo("v1");
        }
    }

    @Test
    void aLabelIsTraceableToItsEvidenceAndAvailabilityRatherThanItsOccurrence() {
        runRebuildToActivation("closure-lineage-label");

        for (Tenant tenant : java.util.List.of(first, second)) {
            Map<String, Object> label = jdbcTemplate.queryForMap("""
                    SELECT label.label_value, label.source, label.fraud_type,
                           label.evidence_occurred_at, label.available_at,
                           label.source_chargeback_id, chargeback.reason_code
                      FROM fraud_labels label
                      JOIN chargebacks chargeback
                        ON chargeback.id = label.source_chargeback_id
                       AND chargeback.merchant_id = label.merchant_id
                     WHERE label.merchant_id = ? AND label.payment_attempt_id = ?
                    """, tenant.merchantId(), tenant.paymentAttemptId());

            assertThat(label.get("label_value")).isEqualTo("FRAUD");
            assertThat(label.get("source")).isEqualTo("CHARGEBACK");
            assertThat(label.get("source_chargeback_id")).isNotNull();
            // Availability is the point-in-time fact a dataset may use; occurrence is when the
            // evidence happened. Collapsing them is the leak the replay rows depend on avoiding.
            assertThat((java.time.Instant) toInstant(label.get("available_at")))
                    .isAfterOrEqualTo(toInstant(label.get("evidence_occurred_at")));
        }
    }

    @Test
    void oneTenantsLineageNeverResolvesThroughAnotherTenantsRows() {
        ProjectionRebuildControlService.RebuildView rebuild =
                runRebuildToActivation("closure-lineage-tenancy");

        // Every join in the chain is tenant-qualified, so no position may name an event that
        // belongs to a different merchant.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_stream_positions position
                JOIN domain_events event ON event.id = position.last_domain_event_id
                WHERE position.projection_generation_id = ?
                  AND event.merchant_id <> position.merchant_id
                """, Long.class, rebuild.generationId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fraud_labels label
                JOIN payment_attempts attempt ON attempt.id = label.payment_attempt_id
                WHERE attempt.merchant_id <> label.merchant_id
                """, Long.class)).isZero();
        // And both tenants are actually represented, so the zero above is not an empty scan.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT merchant_id) FROM projection_stream_positions
                WHERE projection_generation_id = ?
                """, Integer.class, rebuild.generationId())).isEqualTo(2);
    }

    private java.time.Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return (java.time.Instant) value;
    }
}
