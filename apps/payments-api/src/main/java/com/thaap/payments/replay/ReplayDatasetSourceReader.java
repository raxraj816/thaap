package com.thaap.payments.replay;

import com.thaap.payments.fraud.FeatureSnapshot;
import com.thaap.payments.fraud.FeatureCatalogue;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.tenant.TrafficOrigin;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ReplayDatasetSourceReader {

    private static final List<String> COMPATIBILITY_FAMILIES = List.of(
            "POPULATION", "ELIGIBILITY", "ORIGINAL_ACTION", "LABEL_OUTCOME",
            "MONEY_SUMMARY", "TRAFFIC_ORIGIN", "CANDIDATE_INPUT_LINEAGE");

    private final JdbcTemplate jdbcTemplate;
    private final ReplayDatasetDigestCodec digestCodec;

    public ReplayDatasetSourceReader(JdbcTemplate jdbcTemplate,
            ReplayDatasetDigestCodec digestCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.digestCodec = digestCodec;
    }

    public List<ReplayDatasetItem> readPage(DatasetBoundary boundary, int pageSize) {
        String keyset = boundary.checkpointCreatedAt() == null ? "" :
                " AND (decision.created_at > ? OR (decision.created_at = ? AND attempt.id > ?))";
        String sql = """
                SELECT attempt.id, decision.id, attempt.payment_intent_id,
                       decision.created_at, decision.decision_completed_at,
                       attempt.traffic_origin, decision.action,
                       intent.amount, intent.currency,
                       COALESCE(payment_authorization.authorized_amount, 0),
                       COALESCE((SELECT SUM(entry.amount) FROM ledger_entries entry
                                  WHERE entry.merchant_id = attempt.merchant_id
                                    AND entry.source_type = 'PAYMENT_INTENT_CAPTURE'
                                    AND entry.source_id = attempt.payment_intent_id
                                    AND entry.account = 'MERCHANT_PAYABLE'
                                    AND entry.direction = 'CREDIT'
                                    AND entry.created_at <= ?), 0),
                       COALESCE((SELECT SUM(refund.amount) FROM refunds refund
                                  WHERE refund.merchant_id = attempt.merchant_id
                                    AND refund.payment_attempt_id = attempt.id
                                    AND refund.created_at <= ?), 0),
                       snapshot.id, snapshot.feature_version,
                       snapshot.missing_feature_names, snapshot.traffic_origin,
                       (SELECT COUNT(*) FROM feature_values feature_value
                         WHERE feature_value.merchant_id = attempt.merchant_id
                           AND feature_value.payment_attempt_id = attempt.id
                           AND feature_value.created_at <= decision.decision_completed_at),
                       (SELECT COUNT(*) FROM feature_values feature_value
                         WHERE feature_value.merchant_id = attempt.merchant_id
                           AND feature_value.payment_attempt_id = attempt.id
                           AND feature_value.created_at <= decision.decision_completed_at
                           AND feature_value.feature_version <> 'v1'),
                       snapshot.computed_at, snapshot.created_at,
                       (EXISTS (
                           SELECT 1
                             FROM projection_cutoff_stream_positions position
                             JOIN domain_events event
                               ON event.merchant_id = position.merchant_id
                              AND event.stream_type = position.stream_type
                              AND event.stream_id = position.stream_id
                              AND event.stream_version <= position.maximum_stream_version
                             JOIN domain_event_source_lineage lineage
                               ON lineage.merchant_id = event.merchant_id
                              AND lineage.domain_event_id = event.id
                              AND lineage.source_type = 'FRAUD_DECISION'
                            WHERE position.cutoff_manifest_id = ?
                              AND position.merchant_id = attempt.merchant_id
                              AND position.stream_type = 'PAYMENT'
                              AND position.stream_id = attempt.payment_intent_id
                              AND event.event_type = 'fraud_decision.created'
                              AND lineage.fraud_decision_id = decision.id
                              AND lineage.feature_snapshot_id = snapshot.id)
                        OR EXISTS (
                           SELECT 1
                             FROM projection_cutoff_manifests cutoff
                             JOIN projection_bootstrap_facts fact
                               ON fact.bootstrap_manifest_id = cutoff.bootstrap_manifest_id
                            WHERE cutoff.id = ?
                              AND fact.merchant_id = attempt.merchant_id
                              AND fact.source_type = 'FRAUD_DECISION'
                              AND fact.source_id = decision.id)) AS cutoff_member,
                       (EXISTS (
                           SELECT 1
                             FROM projection_cutoff_stream_positions position
                             JOIN domain_events event
                               ON event.merchant_id = position.merchant_id
                              AND event.stream_type = position.stream_type
                              AND event.stream_id = position.stream_id
                              AND event.stream_version <= position.maximum_stream_version
                             JOIN domain_event_source_lineage lineage
                               ON lineage.merchant_id = event.merchant_id
                              AND lineage.domain_event_id = event.id
                              AND lineage.source_type = 'FRAUD_DECISION'
                            WHERE position.cutoff_manifest_id = ?
                              AND position.merchant_id = attempt.merchant_id
                              AND position.stream_type = 'PAYMENT'
                              AND position.stream_id = attempt.payment_intent_id
                              AND event.event_type = 'fraud_decision.created'
                              AND event.event_version = 1
                              AND lineage.fraud_decision_id = decision.id
                              AND lineage.feature_snapshot_id = snapshot.id)
                        OR EXISTS (
                           SELECT 1
                             FROM projection_cutoff_manifests cutoff
                             JOIN projection_bootstrap_facts fact
                               ON fact.bootstrap_manifest_id = cutoff.bootstrap_manifest_id
                            WHERE cutoff.id = ?
                              AND fact.merchant_id = attempt.merchant_id
                              AND fact.source_type = 'FRAUD_DECISION'
                              AND fact.source_id = decision.id)) AS supported_cutoff_member
                  FROM payment_attempts attempt
                  JOIN payment_intents intent
                    ON intent.merchant_id = attempt.merchant_id
                   AND intent.id = attempt.payment_intent_id
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = attempt.merchant_id
                   AND decision.payment_attempt_id = attempt.id
             LEFT JOIN feature_snapshots snapshot
                    ON snapshot.merchant_id = decision.merchant_id
                   AND snapshot.fraud_decision_id = decision.id
             LEFT JOIN authorizations payment_authorization
                    ON payment_authorization.merchant_id = attempt.merchant_id
                   AND payment_authorization.payment_attempt_id = attempt.id
                   AND payment_authorization.created_at <= ?
                 WHERE attempt.merchant_id = ?
                   AND decision.created_at >= ? AND decision.created_at < ?
                """ + keyset + " ORDER BY decision.created_at, attempt.id LIMIT ?";
        List<Object> parameters = new ArrayList<>();
        parameters.add(Timestamp.from(boundary.sourceAsOf()));
        parameters.add(Timestamp.from(boundary.sourceAsOf()));
        parameters.add(boundary.cutoffManifestId());
        parameters.add(boundary.cutoffManifestId());
        parameters.add(boundary.cutoffManifestId());
        parameters.add(boundary.cutoffManifestId());
        parameters.add(Timestamp.from(boundary.sourceAsOf()));
        parameters.add(boundary.merchantId());
        parameters.add(Timestamp.from(boundary.cohortStart()));
        parameters.add(Timestamp.from(boundary.cohortEnd()));
        if (boundary.checkpointCreatedAt() != null) {
            parameters.add(Timestamp.from(boundary.checkpointCreatedAt()));
            parameters.add(Timestamp.from(boundary.checkpointCreatedAt()));
            parameters.add(boundary.checkpointAttemptId());
        }
        parameters.add(pageSize);
        return jdbcTemplate.query(sql, (resultSet, rowNumber) ->
                materialize(boundary, mapSource(resultSet)), parameters.toArray());
    }

    public long sourceCount(DatasetBoundary boundary) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM payment_attempts attempt
                  JOIN fraud_decisions decision
                    ON decision.merchant_id = attempt.merchant_id
                   AND decision.payment_attempt_id = attempt.id
                 WHERE attempt.merchant_id = ?
                   AND decision.created_at >= ? AND decision.created_at < ?
                """, Long.class, boundary.merchantId(), Timestamp.from(boundary.cohortStart()),
                Timestamp.from(boundary.cohortEnd()));
        return count == null ? 0 : count;
    }

    private SourceRow mapSource(ResultSet resultSet) throws SQLException {
        UUID snapshotId = resultSet.getObject(13, UUID.class);
        return new SourceRow(resultSet.getObject(1, UUID.class),
                resultSet.getObject(2, UUID.class), resultSet.getObject(3, UUID.class),
                resultSet.getTimestamp(4).toInstant(), resultSet.getTimestamp(5).toInstant(),
                TrafficOrigin.valueOf(resultSet.getString(6)), resultSet.getString(7),
                resultSet.getLong(8), resultSet.getString(9), resultSet.getLong(10),
                resultSet.getLong(11), resultSet.getLong(12), snapshotId,
                resultSet.getString(14), resultSet.getString(15), resultSet.getString(16),
                resultSet.getLong(17), resultSet.getLong(18), instant(resultSet.getTimestamp(19)),
                instant(resultSet.getTimestamp(20)), resultSet.getBoolean(21),
                resultSet.getBoolean(22));
    }

    private ReplayDatasetItem materialize(DatasetBoundary boundary, SourceRow source) {
        FeatureLineage features = featureLineage(boundary.merchantId(), source.paymentAttemptId(),
                source.decisionCompletedAt());
        LabelResolution label = labels(boundary.merchantId(), source.paymentAttemptId(),
                boundary.labelAvailabilityCutoff(), boundary.fraudTaxonomyVersion());
        ReplayDatasetExclusionCode exclusion = exclusion(boundary, source, features, label);
        long aggregateAmount = source.authorizationAmount() > 0
                ? source.authorizationAmount() : source.intentAmount();
        Map<String, Object> membershipEvidence = new LinkedHashMap<>();
        membershipEvidence.put("decisionCreatedAt", source.decisionCreatedAt().toString());
        membershipEvidence.put("decisionId", source.fraudDecisionId().toString());
        membershipEvidence.put("eventCutoffManifestSha256", boundary.eventCutoffDigest());
        membershipEvidence.put("paymentAttemptId", source.paymentAttemptId().toString());
        membershipEvidence.put("trafficOrigin", source.trafficOrigin().name());
        String membershipDigest = digestCodec.digest(membershipEvidence);
        Map<String, Object> itemEvidence = new LinkedHashMap<>();
        itemEvidence.put("action", source.originalAction());
        itemEvidence.put("authorizationAmountMinor", source.authorizationAmount());
        itemEvidence.put("captureAmountMinor", source.captureAmount());
        itemEvidence.put("currency", source.currency());
        itemEvidence.put("decisionCreatedAt", source.decisionCreatedAt().toString());
        itemEvidence.put("decisionId", source.fraudDecisionId().toString());
        itemEvidence.put("eventCutoffManifestSha256", boundary.eventCutoffDigest());
        itemEvidence.put("exclusionCode", exclusion == null ? null : exclusion.name());
        itemEvidence.put("featureLineageSha256", features.digest());
        itemEvidence.put("labelOutcome", label.outcome());
        itemEvidence.put("paymentAttemptId", source.paymentAttemptId().toString());
        itemEvidence.put("refundAmountMinor", source.refundAmount());
        itemEvidence.put("trafficOrigin", source.trafficOrigin().name());
        String itemDigest = digestCodec.digest(itemEvidence);

        SimulationResult simulation = boundary.simulationRunId() == null ? null
                : simulation(boundary.merchantId(), boundary.simulationRunId(),
                        source.paymentAttemptId());
        List<CompatibilityEvidence> compatibility = boundary.simulationRunId() == null
                ? List.of() : compare(boundary, source, features, label, exclusion, simulation);
        String compatibilityItemDigest = compatibility.isEmpty() ? null
                : digestCodec.digest(Map.of("families", compatibility.stream()
                        .map(value -> value.family() + ":" + value.status()
                                + ":" + nullSafe(value.expectedSha256())
                                + ":" + nullSafe(value.actualSha256()))
                        .toList()));
        return new ReplayDatasetItem(source.paymentAttemptId(), source.decisionCreatedAt(),
                source.trafficOrigin(), source.originalAction(), source.currency(), aggregateAmount,
                source.authorizationAmount(), source.captureAmount(), source.refundAmount(),
                label.outcome(), label.superseded(), exclusion, membershipDigest, itemDigest,
                compatibilityItemDigest, compatibility);
    }

    private ReplayDatasetExclusionCode exclusion(DatasetBoundary boundary, SourceRow source,
            FeatureLineage features, LabelResolution label) {
        if (!boundary.originPolicy().includes(source.trafficOrigin())) {
            return ReplayDatasetExclusionCode.SYNTHETIC_EXCLUDED;
        }
        if (!source.cutoffMember()
                || (source.snapshotId() != null
                    && (!source.trafficOrigin().name().equals(source.snapshotTrafficOrigin())
                        || source.snapshotComputedAt() == null
                        || source.snapshotCreatedAt() == null
                        || source.snapshotComputedAt().isAfter(source.decisionCompletedAt())))) {
            return ReplayDatasetExclusionCode.CORRUPT_LINEAGE;
        }
        if (!source.supportedCutoffMember()
                || (source.snapshotId() != null
                    && !FeatureSnapshot.FEATURES_V1.equals(source.snapshotVersion()))
                || source.unsupportedFeatureValueCount() > 0
                || !label.supportedTaxonomy()) {
            return ReplayDatasetExclusionCode.UNSUPPORTED_VERSION;
        }
        if (source.snapshotId() == null
                || !containsRequiredFeatures(features)) {
            return ReplayDatasetExclusionCode.MISSING_FEATURE;
        }
        return null;
    }

    private boolean containsRequiredFeatures(FeatureLineage features) {
        return FeatureCatalogue.requiredCodes().stream().allMatch(requiredCode ->
                features.entries().stream().anyMatch(entry -> requiredCode.equals(entry.get("code"))
                        && FeatureCatalogue.VERSION.equals(entry.get("version"))));
    }

    private FeatureLineage featureLineage(UUID merchantId, UUID attemptId,
            Instant decisionCompletedAt) {
        List<Map<String, Object>> entries = jdbcTemplate.query("""
                SELECT id, feature_definition_id, feature_code, feature_version, value_type,
                       long_value, string_value, boolean_value, decimal_value,
                       observed_at, computed_at, created_at, traffic_origin
                  FROM feature_values
                 WHERE merchant_id = ? AND payment_attempt_id = ?
                   AND created_at <= ?
                 ORDER BY feature_code, feature_version
                """, (resultSet, rowNumber) -> featureEntry(resultSet), merchantId, attemptId,
                Timestamp.from(decisionCompletedAt));
        return new FeatureLineage(List.copyOf(entries),
                digestCodec.digest(Map.of("features", entries)));
    }

    private Map<String, Object> featureEntry(ResultSet resultSet) throws SQLException {
        Map<String, Object> entry = new LinkedHashMap<>();
        String type = resultSet.getString(5);
        Object value = switch (type) {
            case "LONG" -> resultSet.getObject(6, Long.class);
            case "STRING" -> resultSet.getString(7);
            case "BOOLEAN" -> resultSet.getObject(8, Boolean.class);
            case "DECIMAL" -> {
                BigDecimal decimal = resultSet.getBigDecimal(9);
                yield decimal == null ? null : decimal.toPlainString();
            }
            default -> null;
        };
        entry.put("code", resultSet.getString(3));
        entry.put("computedAt", resultSet.getTimestamp(11).toInstant().toString());
        entry.put("createdAt", resultSet.getTimestamp(12).toInstant().toString());
        entry.put("definitionId", resultSet.getObject(2, UUID.class).toString());
        entry.put("featureValueId", resultSet.getObject(1, UUID.class).toString());
        entry.put("observedAt", resultSet.getTimestamp(10).toInstant().toString());
        entry.put("trafficOrigin", resultSet.getString(13));
        entry.put("type", type);
        entry.put("value", value);
        entry.put("version", resultSet.getString(4));
        return entry;
    }

    private LabelResolution labels(UUID merchantId, UUID attemptId, Instant cutoff,
            String expectedTaxonomy) {
        List<LabelRow> labels = jdbcTemplate.query("""
                SELECT label.label_value, label.taxonomy_version,
                       EXISTS (SELECT 1 FROM fraud_labels successor
                                WHERE successor.merchant_id = label.merchant_id
                                  AND successor.supersedes_label_id = label.id
                                  AND successor.available_at <= ?)
                  FROM fraud_labels label
                 WHERE label.merchant_id = ? AND label.payment_attempt_id = ?
                   AND label.available_at <= ?
                 ORDER BY label.available_at, label.id
                """, (resultSet, rowNumber) -> new LabelRow(resultSet.getString(1),
                        resultSet.getString(2), resultSet.getBoolean(3)),
                Timestamp.from(cutoff), merchantId, attemptId, Timestamp.from(cutoff));
        boolean fraud = labels.stream().anyMatch(label -> !label.superseded()
                && "FRAUD".equals(label.value()));
        boolean legitimate = labels.stream().anyMatch(label -> !label.superseded()
                && "LEGITIMATE".equals(label.value()));
        String outcome = fraud && legitimate ? "CONTRADICTORY"
                : fraud ? "FRAUD" : legitimate ? "LEGITIMATE" : "UNLABELED";
        boolean supported = labels.stream().allMatch(label -> expectedTaxonomy.equals(label.taxonomyVersion()));
        boolean superseded = labels.stream().anyMatch(LabelRow::superseded);
        return new LabelResolution(outcome, superseded, supported);
    }

    private SimulationResult simulation(UUID merchantId, UUID runId, UUID attemptId) {
        return jdbcTemplate.query("""
                SELECT payment_attempt_id, fraud_decision_id, decision_created_at,
                       eligibility_status, exclusion_reason, label_outcome,
                       original_action, authorization_amount, capture_amount,
                       refund_amount, traffic_origin
                  FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                   AND payment_attempt_id = ?
                """, resultSet -> resultSet.next() ? new SimulationResult(
                        resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                        resultSet.getTimestamp(3).toInstant(), resultSet.getString(4),
                        resultSet.getString(5), resultSet.getString(6), resultSet.getString(7),
                        resultSet.getLong(8), resultSet.getLong(9), resultSet.getLong(10),
                        resultSet.getString(11)) : null,
                merchantId, runId, attemptId);
    }

    private List<CompatibilityEvidence> compare(DatasetBoundary boundary, SourceRow source,
            FeatureLineage features, LabelResolution label, ReplayDatasetExclusionCode exclusion,
            SimulationResult simulation) {
        List<CompatibilityEvidence> evidence = new ArrayList<>();
        boolean expectedPresent = boundary.originPolicy().includes(source.trafficOrigin());
        add(evidence, "POPULATION", expectedPresent, simulation != null);
        String datasetEligibility = eligibility(exclusion);
        String simulationEligibility = simulation == null ? "ABSENT"
                : simulation.eligibilityStatus() + ":" + nullSafe(simulation.exclusionReason());
        String expectedEligibility = exclusion == ReplayDatasetExclusionCode.SYNTHETIC_EXCLUDED
                ? "ABSENT" : datasetEligibility;
        add(evidence, "ELIGIBILITY", expectedEligibility, simulationEligibility);
        add(evidence, "ORIGINAL_ACTION", expectedPresent ? source.originalAction() : "ABSENT",
                simulation == null ? "ABSENT" : simulation.originalAction());
        add(evidence, "LABEL_OUTCOME", expectedPresent ? label.outcome() : "ABSENT",
                simulation == null ? "ABSENT" : simulation.labelOutcome());
        String money = source.authorizationAmount() + ":" + source.captureAmount()
                + ":" + source.refundAmount();
        String simulationMoney = simulation == null ? "ABSENT"
                : simulation.authorizationAmount() + ":" + simulation.captureAmount()
                        + ":" + simulation.refundAmount();
        add(evidence, "MONEY_SUMMARY", expectedPresent ? money : "ABSENT", simulationMoney);
        add(evidence, "TRAFFIC_ORIGIN",
                expectedPresent ? source.trafficOrigin().name() : "ABSENT",
                simulation == null ? "ABSENT" : simulation.trafficOrigin());
        String featureExpected = expectedPresent
                ? candidateInputLineage(source.paymentAttemptId(), source.fraudDecisionId(),
                        source.decisionCreatedAt(), source.paymentAttemptId(),
                        source.decisionCreatedAt(), source.snapshotId(), features)
                : "ABSENT";
        String featureActual = simulation == null ? "ABSENT"
                : simulationCandidateInputLineage(boundary.merchantId(), simulation);
        add(evidence, "CANDIDATE_INPUT_LINEAGE", featureExpected, featureActual);
        if (evidence.size() != COMPATIBILITY_FAMILIES.size()) {
            throw new IllegalStateException("Replay compatibility family set is incomplete");
        }
        return List.copyOf(evidence);
    }

    private String simulationCandidateInputLineage(UUID merchantId, SimulationResult simulation) {
        SimulationDecisionLineage source = jdbcTemplate.query("""
                SELECT decision.payment_attempt_id, decision.created_at,
                       decision.decision_completed_at, snapshot.id
                  FROM fraud_decisions decision
             LEFT JOIN feature_snapshots snapshot
                    ON snapshot.merchant_id = decision.merchant_id
                   AND snapshot.fraud_decision_id = decision.id
                 WHERE decision.merchant_id = ? AND decision.id = ?
                """, resultSet -> resultSet.next() ? new SimulationDecisionLineage(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getTimestamp(2).toInstant(),
                        resultSet.getTimestamp(3).toInstant(),
                        resultSet.getObject(4, UUID.class)) : null,
                merchantId, simulation.fraudDecisionId());
        if (source == null) {
            return "UNRESOLVED";
        }
        FeatureLineage features = featureLineage(merchantId, source.paymentAttemptId(),
                source.decisionCompletedAt());
        return candidateInputLineage(simulation.paymentAttemptId(),
                simulation.fraudDecisionId(), simulation.decisionCreatedAt(),
                source.paymentAttemptId(), source.decisionCreatedAt(), source.snapshotId(),
                features);
    }

    private String candidateInputLineage(UUID resultPaymentAttemptId, UUID decisionId,
            Instant resultDecisionCreatedAt, UUID sourcePaymentAttemptId,
            Instant sourceDecisionCreatedAt, UUID snapshotId, FeatureLineage features) {
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("decisionId", decisionId.toString());
        lineage.put("featureLineageSha256", features.digest());
        lineage.put("resultDecisionCreatedAt", resultDecisionCreatedAt.toString());
        lineage.put("resultPaymentAttemptId", resultPaymentAttemptId.toString());
        lineage.put("snapshotId", snapshotId == null ? null : snapshotId.toString());
        lineage.put("sourceDecisionCreatedAt", sourceDecisionCreatedAt.toString());
        lineage.put("sourcePaymentAttemptId", sourcePaymentAttemptId.toString());
        return digestCodec.digest(lineage);
    }

    private String eligibility(ReplayDatasetExclusionCode exclusion) {
        if (exclusion == null) {
            return "ELIGIBLE:";
        }
        return switch (exclusion) {
            case MISSING_FEATURE -> "EXCLUDED:MISSING_FEATURE";
            case UNSUPPORTED_VERSION -> "EXCLUDED:UNSUPPORTED_CATALOGUE";
            case CORRUPT_LINEAGE -> "EXCLUDED:CORRUPT_LINEAGE";
            case SYNTHETIC_EXCLUDED -> "ABSENT";
        };
    }

    private void add(List<CompatibilityEvidence> evidence, String family,
            boolean expected, boolean actual) {
        add(evidence, family, Boolean.toString(expected), Boolean.toString(actual));
    }

    private void add(List<CompatibilityEvidence> evidence, String family,
            String expected, String actual) {
        String expectedDigest = SecretHasher.sha256(expected);
        String actualDigest = SecretHasher.sha256(actual);
        evidence.add(new CompatibilityEvidence(family,
                expected.equals(actual) ? "PASS" : "FAIL", expectedDigest, actualDigest));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record DatasetBoundary(UUID datasetId, UUID merchantId, UUID cutoffManifestId,
            String eventCutoffDigest, UUID simulationRunId, Instant cohortStart,
            Instant cohortEnd, Instant sourceAsOf, Instant labelAvailabilityCutoff,
            String fraudTaxonomyVersion, ReplayDatasetTrafficOriginPolicy originPolicy,
            Instant checkpointCreatedAt, UUID checkpointAttemptId) { }

    public record ReplayDatasetItem(UUID paymentAttemptId, Instant decisionCreatedAt,
            TrafficOrigin trafficOrigin, String originalAction, String currency,
            long aggregateAmount, long authorizationAmount, long captureAmount,
            long refundAmount, String labelOutcome, boolean supersededLabel,
            ReplayDatasetExclusionCode exclusionCode, String membershipSha256, String itemSha256,
            String compatibilityItemSha256,
            List<CompatibilityEvidence> compatibilityEvidence) { }

    public record CompatibilityEvidence(String family, String status,
            String expectedSha256, String actualSha256) { }

    private record SourceRow(UUID paymentAttemptId, UUID fraudDecisionId,
            UUID paymentIntentId, Instant decisionCreatedAt, Instant decisionCompletedAt,
            TrafficOrigin trafficOrigin, String originalAction, long intentAmount,
            String currency, long authorizationAmount, long captureAmount,
            long refundAmount, UUID snapshotId, String snapshotVersion,
            String missingFeatureNames, String snapshotTrafficOrigin,
            long featureValueCount, long unsupportedFeatureValueCount,
            Instant snapshotComputedAt, Instant snapshotCreatedAt,
            boolean cutoffMember, boolean supportedCutoffMember) { }

    private record FeatureLineage(List<Map<String, Object>> entries, String digest) { }

    private record LabelRow(String value, String taxonomyVersion, boolean superseded) { }

    private record LabelResolution(String outcome, boolean superseded,
            boolean supportedTaxonomy) { }

    private record SimulationResult(UUID paymentAttemptId, UUID fraudDecisionId,
            Instant decisionCreatedAt, String eligibilityStatus, String exclusionReason,
            String labelOutcome, String originalAction, long authorizationAmount,
            long captureAmount, long refundAmount, String trafficOrigin) { }

    private record SimulationDecisionLineage(UUID paymentAttemptId, Instant decisionCreatedAt,
            Instant decisionCompletedAt, UUID snapshotId) { }
}
