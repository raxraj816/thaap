package com.thaap.payments.replay;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.event.EventTimePrecision;
import com.thaap.payments.fraud.FeatureCatalogue;
import com.thaap.payments.monitoring.FraudTaxonomyCatalogue;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.simulation.RiskSimulationService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OfflineReplayDatasetService {

    public static final String LABEL_RESOLUTION_POLICY = "CURRENT_AT_CUTOFF";
    public static final String SOURCE_CONTRACT_VERSION = "historical_simulation_v1";
    public static final String WORKER_SCHEMA_VERSION = "offline_replay_v1";

    private final JdbcTemplate jdbcTemplate;
    private final ReplayDatasetDigestCodec digestCodec;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final boolean creationEnabled;
    private final int maximumCohortDays;

    public OfflineReplayDatasetService(JdbcTemplate jdbcTemplate,
            ReplayDatasetDigestCodec digestCodec, AuditLogService auditLogService,
            ApplicationEventPublisher eventPublisher, Clock clock,
            @Value("${thaap.replay-datasets.creation-enabled:true}") boolean creationEnabled,
            @Value("${thaap.replay-datasets.maximum-cohort-days:365}") int maximumCohortDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.digestCodec = digestCodec;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.creationEnabled = creationEnabled;
        this.maximumCohortDays = maximumCohortDays;
    }

    @Transactional
    public ReplayDatasetView create(RiskOperatorContext context, UUID cutoffManifestId,
            UUID projectionGenerationId, UUID compatibilitySimulationRunId,
            Instant cohortStart, Instant cohortEnd, Instant labelAvailabilityCutoff,
            ReplayDatasetTrafficOriginPolicy trafficOriginPolicy, String requestId) {
        if (!creationEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Offline replay dataset creation is disabled");
        }
        authorizeCreateReplay(context);
        lockMerchantDatasetCreation(context.merchantId());
        if (cutoffManifestId == null || projectionGenerationId == null
                || trafficOriginPolicy == null) {
            throw badRequest("Cutoff, projection generation and traffic-origin policy are required");
        }
        Instant now = EventTimePrecision.toPostgreSqlMicroseconds(clock.instant());
        Instant normalizedStart = requireMicros(cohortStart, "cohortStart");
        Instant normalizedEnd = requireMicros(cohortEnd, "cohortEnd");
        Instant normalizedLabelCutoff = requireMicros(
                labelAvailabilityCutoff, "labelAvailabilityCutoff");
        if (!normalizedStart.isBefore(normalizedEnd)
                || normalizedEnd.isAfter(normalizedLabelCutoff)
                || normalizedLabelCutoff.isAfter(now)
                || Duration.between(normalizedStart, normalizedEnd)
                        .compareTo(Duration.ofDays(maximumCohortDays)) > 0) {
            throw badRequest("Replay dataset time boundary is invalid");
        }

        CutoffLineage cutoff = requireCutoffLineage(context.merchantId(),
                cutoffManifestId, projectionGenerationId, trafficOriginPolicy);
        if (normalizedEnd.isAfter(cutoff.createdAt())) {
            throw badRequest("Replay cohort extends beyond its immutable event cutoff");
        }
        SimulationLineage simulation = compatibilitySimulationRunId == null ? null
                : requireSimulationLineage(context.merchantId(), compatibilitySimulationRunId,
                        normalizedStart, normalizedEnd, normalizedLabelCutoff,
                        trafficOriginPolicy, cutoff.createdAt());

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("bootstrapManifestSha256", cutoff.bootstrapDigest());
        query.put("cohortEnd", normalizedEnd.toString());
        query.put("cohortStart", normalizedStart.toString());
        query.put("compatibilitySimulationRunId",
                simulation == null ? null : simulation.runId().toString());
        query.put("eventCutoffManifestId", cutoffManifestId.toString());
        query.put("eventCutoffManifestSha256", cutoff.eventCutoffDigest());
        query.put("evaluatorVersion", RiskSimulationService.EVALUATOR_VERSION);
        query.put("featureCatalogueVersion", FeatureCatalogue.VERSION);
        query.put("fraudTaxonomyVersion", FraudTaxonomyCatalogue.VERSION);
        query.put("labelAvailabilityCutoff", normalizedLabelCutoff.toString());
        query.put("labelResolutionPolicy", LABEL_RESOLUTION_POLICY);
        query.put("merchantId", context.merchantId().toString());
        query.put("projectionGenerationId", projectionGenerationId.toString());
        query.put("projectionVersion", cutoff.handlerVersion());
        query.put("sourceContractVersion", SOURCE_CONTRACT_VERSION);
        query.put("trafficOriginPolicy", trafficOriginPolicy.name());
        query.put("workerSchemaVersion", WORKER_SCHEMA_VERSION);
        String queryDigest = digestCodec.digest(query);

        ReplayDatasetView existing = findByQueryDigest(context.merchantId(), queryDigest);
        if (existing != null) {
            return existing;
        }
        Map<String, Object> manifest = new LinkedHashMap<>(query);
        manifest.put("querySha256", queryDigest);
        String manifestDigest = digestCodec.digest(manifest);
        UUID datasetId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO replay_dataset_manifests
                    (id, merchant_id, created_by_operator_id, cutoff_manifest_id,
                     projection_generation_id, compatibility_simulation_run_id,
                     cohort_start, cohort_end, label_availability_cutoff,
                     projection_version, evaluator_version, feature_catalogue_version,
                     fraud_taxonomy_version, traffic_origin_policy,
                     label_resolution_policy, source_contract_version,
                     worker_schema_version, bootstrap_manifest_sha256,
                     event_cutoff_manifest_sha256, query_sha256,
                     dataset_manifest_sha256, lifecycle_status,
                     fencing_epoch, checkpoint_ordinal, processed_count,
                     eligible_count, included_count, unlabeled_count,
                     contradictory_label_count, superseded_label_count,
                     synthetic_excluded_count, unsupported_event_count,
                     missing_feature_count, corrupt_lineage_count,
                     compatibility_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'QUEUED', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        'PENDING', ?, ?)
                """, datasetId, context.merchantId(), context.operatorId(), cutoffManifestId,
                projectionGenerationId, compatibilitySimulationRunId,
                Timestamp.from(normalizedStart), Timestamp.from(normalizedEnd),
                Timestamp.from(normalizedLabelCutoff), cutoff.handlerVersion(),
                RiskSimulationService.EVALUATOR_VERSION, FeatureCatalogue.VERSION,
                FraudTaxonomyCatalogue.VERSION, trafficOriginPolicy.name(),
                LABEL_RESOLUTION_POLICY, SOURCE_CONTRACT_VERSION, WORKER_SCHEMA_VERSION,
                cutoff.bootstrapDigest(), cutoff.eventCutoffDigest(), queryDigest,
                manifestDigest, Timestamp.from(now), Timestamp.from(now));
        auditLogService.recordRiskOperatorAction(context, requestId,
                "offline_replay_dataset.created", "OfflineReplayDataset", datasetId,
                "POINT_IN_TIME_DATASET_REQUESTED", Map.of(),
                Map.of("status", "QUEUED", "trafficOriginPolicy", trafficOriginPolicy.name(),
                        "compatibilityRequested", compatibilitySimulationRunId != null));
        eventPublisher.publishEvent(new ReplayDatasetQueuedEvent(datasetId));
        return requireView(context.merchantId(), datasetId);
    }

    @Transactional
    public void authorizeCreateReplay(RiskOperatorContext context) {
        String role = lockActiveOperator(context);
        if (!RiskOperatorRole.RISK_MANAGER.name().equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "RISK_MANAGER role is required for offline replay dataset creation");
        }
    }

    @Transactional
    public ReplayDatasetView get(RiskOperatorContext context, UUID datasetId) {
        lockActiveOperator(context);
        return requireView(context.merchantId(), datasetId);
    }

    private CutoffLineage requireCutoffLineage(UUID merchantId, UUID cutoffManifestId,
            UUID generationId, ReplayDatasetTrafficOriginPolicy requestedOrigin) {
        CutoffLineage cutoff = jdbcTemplate.query("""
                SELECT cutoff.canonical_manifest_sha256,
                       cutoff.bootstrap_manifest_sha256, cutoff.created_at,
                       generation.handler_version, cutoff.traffic_origin_policy
                  FROM projection_cutoff_manifests cutoff
                  JOIN projection_definitions definition
                    ON definition.id = cutoff.projection_definition_id
                  JOIN projection_generations generation
                    ON generation.projection_definition_id = definition.id
                   AND generation.id = ?
                 WHERE cutoff.id = ?
                   AND definition.projection_name = 'payment-risk-v1'
                   AND generation.source_cutoff_digest = cutoff.canonical_manifest_sha256
                   AND generation.lifecycle_status IN ('RECONCILED', 'ACTIVE', 'RETIRED')
                   AND (
                       EXISTS (SELECT 1 FROM projection_cutoff_stream_positions position
                                WHERE position.cutoff_manifest_id = cutoff.id
                                  AND position.merchant_id = ?)
                       OR EXISTS (
                           SELECT 1 FROM projection_bootstrap_facts fact
                            JOIN projection_bootstrap_manifests bootstrap
                              ON bootstrap.id = fact.bootstrap_manifest_id
                           WHERE cutoff.bootstrap_manifest_id = bootstrap.id
                             AND fact.merchant_id = ?))
                """, resultSet -> resultSet.next()
                        ? new CutoffLineage(resultSet.getString(1), resultSet.getString(2),
                                resultSet.getTimestamp(3).toInstant(), resultSet.getString(4),
                                ReplayDatasetTrafficOriginPolicy.valueOf(resultSet.getString(5)))
                        : null, generationId, cutoffManifestId, merchantId, merchantId);
        if (cutoff == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Replay dataset cutoff was not found");
        }
        if (!originSubset(requestedOrigin, cutoff.originPolicy())) {
            throw badRequest("Replay origin policy is broader than the immutable cutoff");
        }
        return cutoff;
    }

    private SimulationLineage requireSimulationLineage(UUID merchantId, UUID simulationRunId,
            Instant cohortStart, Instant cohortEnd, Instant labelCutoff,
            ReplayDatasetTrafficOriginPolicy originPolicy, Instant eventCutoffCreatedAt) {
        SimulationLineage simulation = jdbcTemplate.query("""
                SELECT cohort_start, cohort_end, as_of, label_cutoff,
                       traffic_origin_policy, evaluator_version,
                       feature_catalogue_version, taxonomy_version, lifecycle_status
                  FROM risk_simulation_runs
                 WHERE merchant_id = ? AND id = ?
                """, resultSet -> resultSet.next()
                        ? new SimulationLineage(simulationRunId,
                                resultSet.getTimestamp(1).toInstant(),
                                resultSet.getTimestamp(2).toInstant(),
                                resultSet.getTimestamp(3).toInstant(),
                                resultSet.getTimestamp(4).toInstant(),
                                resultSet.getString(5), resultSet.getString(6),
                                resultSet.getString(7), resultSet.getString(8),
                                resultSet.getString(9))
                        : null, merchantId, simulationRunId);
        if (simulation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Compatibility simulation was not found");
        }
        String expectedOrigin = originPolicy == ReplayDatasetTrafficOriginPolicy.LIVE_ONLY
                ? "LIVE_ONLY" : "INCLUDE_SYNTHETIC";
        if (!"SUCCEEDED".equals(simulation.lifecycleStatus())
                || !simulation.cohortStart().equals(cohortStart)
                || !simulation.cohortEnd().equals(cohortEnd)
                || simulation.asOf().isAfter(eventCutoffCreatedAt)
                || !simulation.labelCutoff().equals(labelCutoff)
                || !expectedOrigin.equals(simulation.originPolicy())
                || !RiskSimulationService.EVALUATOR_VERSION.equals(simulation.evaluatorVersion())
                || !FeatureCatalogue.VERSION.equals(simulation.featureCatalogueVersion())
                || !FraudTaxonomyCatalogue.VERSION.equals(simulation.taxonomyVersion())
                || originPolicy == ReplayDatasetTrafficOriginPolicy.SYNTHETIC_ONLY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Compatibility simulation does not match the replay manifest boundary");
        }
        return simulation;
    }

    private boolean originSubset(ReplayDatasetTrafficOriginPolicy requested,
            ReplayDatasetTrafficOriginPolicy cutoff) {
        return cutoff == ReplayDatasetTrafficOriginPolicy.ALL || requested == cutoff;
    }

    private String lockActiveOperator(RiskOperatorContext context) {
        String role = jdbcTemplate.query("""
                SELECT role FROM risk_operators
                 WHERE merchant_id = ? AND id = ? AND status = 'ACTIVE'
                 FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                context.merchantId(), context.operatorId());
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Risk operator credential is no longer active");
        }
        return role;
    }

    private void lockMerchantDatasetCreation(UUID merchantId) {
        UUID lockedMerchantId = jdbcTemplate.query("""
                SELECT id FROM merchants WHERE id = ? FOR UPDATE
                """, resultSet -> resultSet.next()
                        ? resultSet.getObject(1, UUID.class) : null, merchantId);
        if (lockedMerchantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Risk operator merchant is unavailable");
        }
    }

    private ReplayDatasetView findByQueryDigest(UUID merchantId, String queryDigest) {
        UUID datasetId = jdbcTemplate.query("""
                SELECT id FROM replay_dataset_manifests
                 WHERE merchant_id = ? AND query_sha256 = ?
                """, resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                merchantId, queryDigest);
        return datasetId == null ? null : requireView(merchantId, datasetId);
    }

    private ReplayDatasetView requireView(UUID merchantId, UUID datasetId) {
        ReplayDatasetView view = jdbcTemplate.query("""
                SELECT id, cutoff_manifest_id, event_cutoff_manifest_sha256,
                       bootstrap_manifest_sha256, projection_generation_id,
                       compatibility_simulation_run_id, cohort_start, cohort_end,
                       label_availability_cutoff, projection_version, evaluator_version,
                       feature_catalogue_version, fraud_taxonomy_version,
                       traffic_origin_policy, label_resolution_policy,
                       source_contract_version, worker_schema_version,
                       query_sha256, dataset_manifest_sha256, lifecycle_status,
                       processed_count, included_count, unlabeled_count,
                       contradictory_label_count, superseded_label_count,
                       synthetic_excluded_count, unsupported_event_count,
                       missing_feature_count, corrupt_lineage_count,
                       population_sha256, output_sha256, compatibility_status,
                       compatibility_sha256, compatibility_failure_code, failure_code,
                       created_at, updated_at, completed_at
                  FROM replay_dataset_manifests
                 WHERE merchant_id = ? AND id = ?
                """, resultSet -> resultSet.next() ? mapView(resultSet, merchantId) : null,
                merchantId, datasetId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Offline replay dataset was not found");
        }
        return view;
    }

    private ReplayDatasetView mapView(ResultSet resultSet, UUID merchantId) throws SQLException {
        UUID datasetId = resultSet.getObject(1, UUID.class);
        List<ReplayDatasetAggregate> aggregates = jdbcTemplate.query("""
                SELECT metric_code, bucket_code, population_count, amount_minor,
                       result_sha256
                  FROM replay_dataset_results
                 WHERE merchant_id = ? AND replay_dataset_id = ?
                 ORDER BY metric_code, bucket_code
                """, (rows, rowNumber) -> new ReplayDatasetAggregate(
                        ReplayDatasetMetricCode.valueOf(rows.getString(1)), rows.getString(2),
                        rows.getLong(3), rows.getObject(4, Long.class), rows.getString(5)),
                merchantId, datasetId);
        Coverage coverage = new Coverage(resultSet.getLong(21), resultSet.getLong(22),
                resultSet.getLong(23), resultSet.getLong(24), resultSet.getLong(25),
                resultSet.getLong(26), resultSet.getLong(27), resultSet.getLong(28),
                resultSet.getLong(29));
        Timestamp completedAt = resultSet.getTimestamp(38);
        UUID simulationId = resultSet.getObject(6, UUID.class);
        return new ReplayDatasetView(datasetId, resultSet.getObject(2, UUID.class),
                resultSet.getString(3), resultSet.getString(4),
                resultSet.getObject(5, UUID.class), simulationId,
                resultSet.getTimestamp(7).toInstant(), resultSet.getTimestamp(8).toInstant(),
                resultSet.getTimestamp(9).toInstant(), resultSet.getString(10),
                resultSet.getString(11), resultSet.getString(12), resultSet.getString(13),
                ReplayDatasetTrafficOriginPolicy.valueOf(resultSet.getString(14)),
                resultSet.getString(15), resultSet.getString(16), resultSet.getString(17),
                resultSet.getString(18), resultSet.getString(19),
                ReplayDatasetLifecycleStatus.valueOf(resultSet.getString(20)), coverage,
                resultSet.getString(30), resultSet.getString(31),
                ReplayDatasetCompatibilityStatus.valueOf(resultSet.getString(32)),
                resultSet.getString(33), resultSet.getString(34), resultSet.getString(35),
                resultSet.getTimestamp(36).toInstant(), resultSet.getTimestamp(37).toInstant(),
                completedAt == null ? null : completedAt.toInstant(), aggregates);
    }

    private Instant requireMicros(Instant value, String field) {
        if (value == null) {
            throw badRequest(field + " is required");
        }
        Instant normalized = EventTimePrecision.toPostgreSqlMicroseconds(value);
        if (!normalized.equals(value)) {
            throw badRequest(field + " must have at most microsecond precision");
        }
        return normalized;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record CutoffLineage(String eventCutoffDigest, String bootstrapDigest,
            Instant createdAt, String handlerVersion,
            ReplayDatasetTrafficOriginPolicy originPolicy) { }

    private record SimulationLineage(UUID runId, Instant cohortStart, Instant cohortEnd,
            Instant asOf, Instant labelCutoff, String originPolicy, String evaluatorVersion,
            String featureCatalogueVersion, String taxonomyVersion, String lifecycleStatus) { }

    public record Coverage(long processedCount, long includedCount, long unlabeledCount,
            long contradictoryLabelCount, long supersededLabelCount,
            long syntheticExcludedCount, long unsupportedVersionCount,
            long missingFeatureCount, long corruptLineageCount) { }

    public record ReplayDatasetAggregate(ReplayDatasetMetricCode metricCode, String bucketCode,
            long populationCount, Long amountMinor, String resultSha256) { }

    public record ReplayDatasetView(UUID datasetId, UUID eventCutoffManifestId,
            String eventCutoffManifestSha256, String bootstrapManifestSha256,
            UUID projectionGenerationId, UUID compatibilitySimulationRunId,
            Instant cohortStart, Instant cohortEnd, Instant labelAvailabilityCutoff,
            String projectionVersion, String evaluatorVersion,
            String featureCatalogueVersion, String fraudTaxonomyVersion,
            ReplayDatasetTrafficOriginPolicy trafficOriginPolicy,
            String labelResolutionPolicy, String sourceContractVersion,
            String workerSchemaVersion, String querySha256, String datasetManifestSha256,
            ReplayDatasetLifecycleStatus lifecycleStatus, Coverage coverage,
            String populationSha256, String outputSha256,
            ReplayDatasetCompatibilityStatus compatibilityStatus,
            String compatibilitySha256, String compatibilityFailureCode,
            String failureCode, Instant createdAt, Instant updatedAt,
            Instant completedAt, List<ReplayDatasetAggregate> aggregates) { }
}
