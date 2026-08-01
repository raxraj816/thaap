package com.thaap.payments.replay;

import com.thaap.payments.event.EventTimePrecision;
import com.thaap.payments.replay.ReplayDatasetSourceReader.CompatibilityEvidence;
import com.thaap.payments.replay.ReplayDatasetSourceReader.DatasetBoundary;
import com.thaap.payments.replay.ReplayDatasetSourceReader.ReplayDatasetItem;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfflineReplayDatasetWorker {

    private static final List<String> COMPATIBILITY_FAMILIES = List.of(
            "POPULATION", "ELIGIBILITY", "ORIGINAL_ACTION", "LABEL_OUTCOME",
            "MONEY_SUMMARY", "TRAFFIC_ORIGIN", "CANDIDATE_INPUT_LINEAGE");
    private static final int MINIMUM_AMOUNT_GROUP_SIZE = 3;

    private final JdbcTemplate jdbcTemplate;
    private final ReplayDatasetSourceReader sourceReader;
    private final ReplayDatasetDigestCodec digestCodec;
    private final OfflineReplayDatasetExecutionProbe executionProbe;
    private final OfflineReplayDatasetMetrics metrics;
    private final Clock clock;
    private final int chunkSize;
    private final Duration leaseDuration;
    private final int retryLimit;
    private final long maximumPopulation;

    public OfflineReplayDatasetWorker(JdbcTemplate jdbcTemplate,
            ReplayDatasetSourceReader sourceReader, ReplayDatasetDigestCodec digestCodec,
            OfflineReplayDatasetExecutionProbe executionProbe,
            OfflineReplayDatasetMetrics metrics, Clock clock,
            @Value("${thaap.replay-datasets.chunk-size:100}") int chunkSize,
            @Value("${thaap.replay-datasets.lease-duration:60s}") Duration leaseDuration,
            @Value("${thaap.replay-datasets.retry-limit:10}") int retryLimit,
            @Value("${thaap.replay-datasets.maximum-population:1000000}")
                    long maximumPopulation) {
        if (chunkSize < 1 || chunkSize > 1000) {
            throw new IllegalArgumentException("Replay dataset chunk size must be between 1 and 1000");
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("Replay dataset lease duration must be positive");
        }
        if (retryLimit < 1 || retryLimit > 100) {
            throw new IllegalArgumentException("Replay dataset retry limit must be between 1 and 100");
        }
        if (maximumPopulation < 1) {
            throw new IllegalArgumentException("Replay dataset maximum population must be positive");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.sourceReader = sourceReader;
        this.digestCodec = digestCodec;
        this.executionProbe = executionProbe;
        this.metrics = metrics;
        this.clock = clock;
        this.chunkSize = chunkSize;
        this.leaseDuration = leaseDuration;
        this.retryLimit = retryLimit;
        this.maximumPopulation = maximumPopulation;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WorkerClaim claim(UUID datasetId, String workerId) {
        Instant now = now();
        ClaimableDataset row = jdbcTemplate.query("""
                SELECT lifecycle_status, lease_expires_at, fencing_epoch
                  FROM replay_dataset_manifests
                 WHERE id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? new ClaimableDataset(
                        ReplayDatasetLifecycleStatus.valueOf(resultSet.getString(1)),
                        instant(resultSet.getTimestamp(2)), resultSet.getLong(3)) : null,
                datasetId);
        if (row == null || (row.status() != ReplayDatasetLifecycleStatus.QUEUED
                && (row.status() != ReplayDatasetLifecycleStatus.RUNNING
                    || row.leaseExpiresAt() == null || row.leaseExpiresAt().isAfter(now)))) {
            return null;
        }
        if (row.status() == ReplayDatasetLifecycleStatus.RUNNING
                && row.fencingEpoch() >= retryLimit) {
            jdbcTemplate.update("""
                    UPDATE replay_dataset_manifests
                       SET lifecycle_status = 'FAILED',
                           failure_code = 'DATASET_PROCESSING_FAILED',
                           lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                           updated_at = ?, completed_at = ?
                     WHERE id = ? AND lifecycle_status = 'RUNNING'
                       AND fencing_epoch = ?
                    """, Timestamp.from(now), Timestamp.from(now), datasetId,
                    row.fencingEpoch());
            metrics.recordJob("retry_exhausted");
            return null;
        }
        UUID leaseToken = UUID.randomUUID();
        long fencingEpoch = row.fencingEpoch() + 1;
        int changed = jdbcTemplate.update("""
                UPDATE replay_dataset_manifests
                   SET lifecycle_status = 'RUNNING', lease_owner = ?, lease_token = ?,
                       lease_expires_at = ?, fencing_epoch = ?, updated_at = ?
                 WHERE id = ? AND fencing_epoch = ?
                """, workerId, leaseToken, Timestamp.from(now.plus(leaseDuration)),
                fencingEpoch, Timestamp.from(now), datasetId, row.fencingEpoch());
        if (changed != 1) {
            return null;
        }
        metrics.recordLease(row.status() == ReplayDatasetLifecycleStatus.QUEUED
                ? "claimed" : "recovered");
        return new WorkerClaim(datasetId, workerId, leaseToken, fencingEpoch);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WorkOutcome processNextChunk(WorkerClaim claim) {
        RunningDataset running = lockRunning(claim);
        if (running == null) {
            return WorkOutcome.STALE;
        }
        assertFrozenSource(running);
        if (running.processedCount() == 0
                && sourceReader.sourceCount(running.boundary()) > maximumPopulation) {
            throw new ReplayDatasetPopulationLimitException(maximumPopulation);
        }
        DatasetBoundary boundary = running.boundary();
        List<ReplayDatasetItem> items = sourceReader.readPage(boundary, chunkSize);
        if (items.isEmpty()) {
            executionProbe.beforeFinalization(claim.datasetId());
            finalizeDataset(claim, running);
            metrics.recordJob("completed");
            return WorkOutcome.COMPLETE;
        }

        long chunkOrdinal = running.checkpointOrdinal() + 1;
        long firstItemOrdinal = running.processedCount() + 1;
        ChunkSummary summary = summarize(items);
        Instant committedAt = now();
        jdbcTemplate.update("""
                INSERT INTO replay_dataset_chunks
                    (id, merchant_id, replay_dataset_id, chunk_ordinal, fencing_epoch,
                     processed_count, included_count, unlabeled_count,
                     contradictory_label_count, superseded_label_count,
                     synthetic_excluded_count, unsupported_version_count,
                     missing_feature_count, corrupt_lineage_count,
                     chunk_population_sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), running.merchantId(), claim.datasetId(), chunkOrdinal,
                claim.fencingEpoch(), items.size(), summary.includedCount(),
                summary.unlabeledCount(), summary.contradictoryCount(),
                summary.supersededCount(), summary.syntheticExcludedCount(),
                summary.unsupportedVersionCount(), summary.missingFeatureCount(),
                summary.corruptLineageCount(), summary.populationDigest(),
                Timestamp.from(committedAt));
        for (int index = 0; index < items.size(); index++) {
            ReplayDatasetItem item = items.get(index);
            long itemOrdinal = firstItemOrdinal + index;
            jdbcTemplate.update("""
                    INSERT INTO replay_dataset_item_evidence
                        (id, merchant_id, replay_dataset_id, item_ordinal,
                         chunk_ordinal, membership_sha256, item_sha256,
                         compatibility_item_sha256,
                         exclusion_code, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), running.merchantId(), claim.datasetId(),
                    itemOrdinal, chunkOrdinal, item.membershipSha256(), item.itemSha256(),
                    item.compatibilityItemSha256(), item.exclusionCode() == null
                            ? null : item.exclusionCode().name(), Timestamp.from(committedAt));
            insertCompatibilityEvidence(running, claim.datasetId(), itemOrdinal,
                    item.compatibilityEvidence(), committedAt);
        }
        for (Map.Entry<AggregateKey, AggregateValue> entry : summary.aggregates().entrySet()) {
            AggregateKey key = entry.getKey();
            AggregateValue value = entry.getValue();
            String resultDigest = aggregateDigest(key, value.count(), value.amountMinor());
            jdbcTemplate.update("""
                    INSERT INTO replay_dataset_chunk_results
                        (id, merchant_id, replay_dataset_id, chunk_ordinal,
                         metric_code, bucket_code, population_count, amount_minor,
                         result_sha256, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), running.merchantId(), claim.datasetId(),
                    chunkOrdinal, key.metricCode().name(), key.bucketCode(), value.count(),
                    value.amountMinor(), resultDigest, Timestamp.from(committedAt));
        }
        executionProbe.afterChunkEvidenceBeforeCheckpoint(claim.datasetId(), chunkOrdinal);
        ReplayDatasetItem checkpoint = items.getLast();
        int changed = jdbcTemplate.update("""
                UPDATE replay_dataset_manifests
                   SET checkpoint_ordinal = ?, checkpoint_decision_created_at = ?,
                       checkpoint_attempt_id = ?, processed_count = processed_count + ?,
                       eligible_count = eligible_count + ?,
                       included_count = included_count + ?,
                       unlabeled_count = unlabeled_count + ?,
                       contradictory_label_count = contradictory_label_count + ?,
                       superseded_label_count = superseded_label_count + ?,
                       synthetic_excluded_count = synthetic_excluded_count + ?,
                       unsupported_event_count = unsupported_event_count + ?,
                       missing_feature_count = missing_feature_count + ?,
                       corrupt_lineage_count = corrupt_lineage_count + ?,
                       lease_expires_at = ?, updated_at = ?
                 WHERE id = ? AND lifecycle_status = 'RUNNING'
                   AND lease_token = ? AND fencing_epoch = ?
                """, chunkOrdinal, Timestamp.from(checkpoint.decisionCreatedAt()),
                checkpoint.paymentAttemptId(), items.size(), items.size(),
                summary.includedCount(), summary.unlabeledCount(),
                summary.contradictoryCount(), summary.supersededCount(),
                summary.syntheticExcludedCount(), summary.unsupportedVersionCount(),
                summary.missingFeatureCount(), summary.corruptLineageCount(),
                Timestamp.from(committedAt.plus(leaseDuration)), Timestamp.from(committedAt),
                claim.datasetId(), claim.leaseToken(), claim.fencingEpoch());
        if (changed != 1) {
            throw new StaleReplayDatasetWorkerException();
        }
        metrics.recordChunk();
        return WorkOutcome.CONTINUE;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(WorkerClaim claim, ReplayDatasetFailureCode failureCode) {
        Instant failedAt = now();
        int changed = jdbcTemplate.update("""
                UPDATE replay_dataset_manifests
                   SET lifecycle_status = 'FAILED', failure_code = ?,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       updated_at = ?, completed_at = ?
                 WHERE id = ? AND lifecycle_status = 'RUNNING'
                   AND lease_token = ? AND fencing_epoch = ?
                """, failureCode.name(), Timestamp.from(failedAt), Timestamp.from(failedAt),
                claim.datasetId(), claim.leaseToken(), claim.fencingEpoch());
        if (changed == 1) {
            metrics.recordJob("failed_" + failureCode.name().toLowerCase());
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> recoverableDatasetIds(int limit) {
        return jdbcTemplate.query("""
                SELECT id FROM replay_dataset_manifests
                 WHERE lifecycle_status = 'QUEUED'
                    OR (lifecycle_status = 'RUNNING' AND lease_expires_at <= ?)
                 ORDER BY created_at, id LIMIT ?
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                Timestamp.from(now()), limit);
    }

    private RunningDataset lockRunning(WorkerClaim claim) {
        return jdbcTemplate.query("""
                SELECT manifest.merchant_id, manifest.cutoff_manifest_id,
                       manifest.event_cutoff_manifest_sha256,
                       manifest.bootstrap_manifest_sha256,
                       manifest.projection_generation_id,
                       manifest.compatibility_simulation_run_id,
                       manifest.cohort_start, manifest.cohort_end,
                       cutoff.created_at, manifest.label_availability_cutoff,
                       manifest.fraud_taxonomy_version, manifest.traffic_origin_policy,
                       manifest.checkpoint_ordinal, manifest.processed_count,
                       manifest.checkpoint_decision_created_at,
                       manifest.checkpoint_attempt_id, manifest.lease_expires_at,
                       cutoff.canonical_manifest_sha256,
                       cutoff.bootstrap_manifest_sha256,
                       generation.source_cutoff_digest,
                       manifest.worker_schema_version,
                       manifest.feature_catalogue_version,
                       manifest.evaluator_version
                  FROM replay_dataset_manifests manifest
                  JOIN projection_cutoff_manifests cutoff
                    ON cutoff.id = manifest.cutoff_manifest_id
                  JOIN projection_generations generation
                    ON generation.id = manifest.projection_generation_id
                 WHERE manifest.id = ? AND manifest.lifecycle_status = 'RUNNING'
                   AND manifest.lease_token = ? AND manifest.fencing_epoch = ?
                 FOR UPDATE
                """, resultSet -> resultSet.next() && leaseActive(resultSet.getTimestamp(17))
                        ? mapRunning(resultSet, claim.datasetId()) : null,
                claim.datasetId(), claim.leaseToken(), claim.fencingEpoch());
    }

    private RunningDataset mapRunning(ResultSet resultSet, UUID datasetId) throws SQLException {
        Instant checkpointTime = instant(resultSet.getTimestamp(15));
        DatasetBoundary boundary = new DatasetBoundary(datasetId,
                resultSet.getObject(1, UUID.class), resultSet.getObject(2, UUID.class),
                resultSet.getString(3), resultSet.getObject(6, UUID.class),
                resultSet.getTimestamp(7).toInstant(), resultSet.getTimestamp(8).toInstant(),
                resultSet.getTimestamp(9).toInstant(), resultSet.getTimestamp(10).toInstant(),
                resultSet.getString(11),
                ReplayDatasetTrafficOriginPolicy.valueOf(resultSet.getString(12)),
                checkpointTime, resultSet.getObject(16, UUID.class));
        return new RunningDataset(resultSet.getObject(1, UUID.class),
                resultSet.getObject(2, UUID.class), resultSet.getObject(5, UUID.class),
                resultSet.getString(3), resultSet.getString(4),
                resultSet.getString(18), resultSet.getString(19), resultSet.getString(20),
                resultSet.getString(21), resultSet.getString(22), resultSet.getString(23),
                resultSet.getLong(13), resultSet.getLong(14), boundary);
    }

    private void assertFrozenSource(RunningDataset running) {
        if (!running.eventCutoffDigest().equals(running.currentEventCutoffDigest())
                || !running.bootstrapDigest().equals(running.currentBootstrapDigest())
                || !running.eventCutoffDigest().equals(running.generationSourceDigest())
                || !OfflineReplayDatasetService.WORKER_SCHEMA_VERSION.equals(
                        running.workerSchemaVersion())
                || !"v1".equals(running.featureCatalogueVersion())
                || !"historical_policy_v1".equals(running.evaluatorVersion())) {
            throw new ReplayDatasetSourceDriftException();
        }
    }

    private ChunkSummary summarize(List<ReplayDatasetItem> items) {
        long included = 0;
        long unlabeled = 0;
        long contradictory = 0;
        long superseded = 0;
        long synthetic = 0;
        long unsupported = 0;
        long missing = 0;
        long corrupt = 0;
        Map<AggregateKey, AggregateValue> aggregates = new TreeMap<>();
        List<String> population = new ArrayList<>();
        for (ReplayDatasetItem item : items) {
            population.add(item.membershipSha256());
            String coverageBucket = item.exclusionCode() == null
                    ? "INCLUDED" : item.exclusionCode().name();
            increment(aggregates, new AggregateKey(
                    ReplayDatasetMetricCode.FEATURE_COVERAGE, coverageBucket), 1, null);
            boolean classificationVisible = item.exclusionCode()
                    != ReplayDatasetExclusionCode.SYNTHETIC_EXCLUDED
                    && item.exclusionCode() != ReplayDatasetExclusionCode.CORRUPT_LINEAGE;
            if (classificationVisible) {
                if ("UNLABELED".equals(item.labelOutcome())) {
                    unlabeled++;
                }
                if ("CONTRADICTORY".equals(item.labelOutcome())) {
                    contradictory++;
                }
                if (item.supersededLabel()) {
                    superseded++;
                }
                increment(aggregates, new AggregateKey(ReplayDatasetMetricCode.LABEL_OUTCOME,
                        item.labelOutcome() + ":" + item.currency()), 1, item.aggregateAmount());
                increment(aggregates, new AggregateKey(ReplayDatasetMetricCode.ORIGINAL_ACTION,
                        item.originalAction() + ":" + item.currency()), 1, item.aggregateAmount());
                increment(aggregates, new AggregateKey(ReplayDatasetMetricCode.TRAFFIC_ORIGIN,
                        item.trafficOrigin().name() + ":" + item.currency()), 1,
                        item.aggregateAmount());
            }
            if (item.exclusionCode() == null) {
                included++;
            } else {
                switch (item.exclusionCode()) {
                    case SYNTHETIC_EXCLUDED -> synthetic++;
                    case UNSUPPORTED_VERSION -> unsupported++;
                    case MISSING_FEATURE -> missing++;
                    case CORRUPT_LINEAGE -> corrupt++;
                }
            }
        }
        String populationDigest = digestCodec.digest(Map.of("items", population));
        return new ChunkSummary(included, unlabeled, contradictory, superseded,
                synthetic, unsupported, missing, corrupt, populationDigest,
                Map.copyOf(aggregates));
    }

    private void increment(Map<AggregateKey, AggregateValue> aggregates, AggregateKey key,
            long count, Long amount) {
        AggregateValue current = aggregates.getOrDefault(key, new AggregateValue(0, null));
        Long nextAmount = current.amountMinor();
        if (amount != null) {
            long currentAmount = nextAmount == null ? 0 : nextAmount;
            nextAmount = Math.addExact(currentAmount, amount);
        }
        aggregates.put(key, new AggregateValue(Math.addExact(current.count(), count), nextAmount));
    }

    private void insertCompatibilityEvidence(RunningDataset running, UUID datasetId,
            long itemOrdinal, List<CompatibilityEvidence> evidence, Instant createdAt) {
        for (CompatibilityEvidence value : evidence) {
            jdbcTemplate.update("""
                    INSERT INTO replay_dataset_compatibility_evidence
                        (id, merchant_id, replay_dataset_id, item_ordinal,
                         invariant_family, result_status, expected_sha256,
                         actual_sha256, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), running.merchantId(), datasetId, itemOrdinal,
                    value.family(), value.status(), value.expectedSha256(), value.actualSha256(),
                    Timestamp.from(createdAt));
        }
    }

    private void finalizeDataset(WorkerClaim claim, RunningDataset running) {
        long sourceCount = sourceReader.sourceCount(running.boundary());
        if (sourceCount != running.processedCount()) {
            throw new ReplayDatasetSourceDriftException();
        }
        Instant completedAt = now();
        List<FinalAggregate> aggregates = finalAggregates(running.merchantId(), claim.datasetId());
        for (FinalAggregate aggregate : aggregates) {
            Long safeAmount = aggregate.metricCode() == ReplayDatasetMetricCode.FEATURE_COVERAGE
                    || aggregate.populationCount() < MINIMUM_AMOUNT_GROUP_SIZE
                            ? null : aggregate.amountMinor();
            insertFinalResult(running.merchantId(), claim.datasetId(), aggregate.metricCode(),
                    aggregate.bucketCode(), aggregate.populationCount(), safeAmount, completedAt);
        }

        CompatibilitySummary compatibility = compatibilitySummary(running, claim.datasetId());
        for (FamilyResult family : compatibility.families()) {
            insertFinalResult(running.merchantId(), claim.datasetId(),
                    ReplayDatasetMetricCode.SIMULATION_EQUIVALENCE,
                    family.family() + ":" + family.status(), family.failureCount(),
                    null, completedAt);
        }
        List<String> populationItems = jdbcTemplate.query("""
                SELECT membership_sha256 FROM replay_dataset_item_evidence
                 WHERE merchant_id = ? AND replay_dataset_id = ?
                 ORDER BY item_ordinal
                """, (resultSet, rowNumber) -> resultSet.getString(1),
                running.merchantId(), claim.datasetId());
        String populationDigest = digestCodec.digest(Map.of("items", populationItems));
        List<String> resultEvidence = jdbcTemplate.query("""
                SELECT metric_code || ':' || bucket_code || ':' || population_count
                       || ':' || COALESCE(CAST(amount_minor AS VARCHAR), '')
                       || ':' || result_sha256
                  FROM replay_dataset_results
                 WHERE merchant_id = ? AND replay_dataset_id = ?
                 ORDER BY metric_code, bucket_code
                """, (resultSet, rowNumber) -> resultSet.getString(1),
                running.merchantId(), claim.datasetId());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("compatibilitySha256", compatibility.digest());
        output.put("corruptLineageCount", runningCount(claim.datasetId(), "corrupt_lineage_count"));
        output.put("datasetId", claim.datasetId().toString());
        output.put("populationSha256", populationDigest);
        output.put("results", resultEvidence);
        String outputDigest = digestCodec.digest(output);
        int changed = jdbcTemplate.update("""
                UPDATE replay_dataset_manifests
                   SET lifecycle_status = 'SUCCEEDED', population_sha256 = ?,
                       output_sha256 = ?, compatibility_status = ?,
                       compatibility_sha256 = ?, compatibility_failure_code = ?,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       updated_at = ?, completed_at = ?
                 WHERE id = ? AND lifecycle_status = 'RUNNING'
                   AND lease_token = ? AND fencing_epoch = ?
                """, populationDigest, outputDigest, compatibility.status().name(),
                compatibility.digest(), compatibility.failureCode(),
                Timestamp.from(completedAt), Timestamp.from(completedAt), claim.datasetId(),
                claim.leaseToken(), claim.fencingEpoch());
        if (changed != 1) {
            throw new StaleReplayDatasetWorkerException();
        }
    }

    private List<FinalAggregate> finalAggregates(UUID merchantId, UUID datasetId) {
        return jdbcTemplate.query("""
                SELECT metric_code, bucket_code, SUM(population_count), SUM(amount_minor)
                  FROM replay_dataset_chunk_results
                 WHERE merchant_id = ? AND replay_dataset_id = ?
                 GROUP BY metric_code, bucket_code
                 ORDER BY metric_code, bucket_code
                """, (resultSet, rowNumber) -> {
                    BigDecimal amount = resultSet.getBigDecimal(4);
                    return new FinalAggregate(ReplayDatasetMetricCode.valueOf(resultSet.getString(1)),
                            resultSet.getString(2), resultSet.getLong(3),
                            amount == null ? null : amount.longValueExact());
                }, merchantId, datasetId);
    }

    private CompatibilitySummary compatibilitySummary(RunningDataset running, UUID datasetId) {
        if (running.boundary().simulationRunId() == null) {
            List<FamilyResult> families = COMPATIBILITY_FAMILIES.stream()
                    .map(family -> new FamilyResult(family, "FAILED", running.processedCount()))
                    .toList();
            String digest = compatibilityDigest(families);
            return new CompatibilitySummary(ReplayDatasetCompatibilityStatus.FAILED,
                    "SIMULATION_NOT_BOUND", digest, families);
        }
        Map<String, Long> failures = new LinkedHashMap<>();
        for (String family : COMPATIBILITY_FAMILIES) {
            Long count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM replay_dataset_compatibility_evidence
                     WHERE merchant_id = ? AND replay_dataset_id = ?
                       AND invariant_family = ? AND result_status = 'FAIL'
                    """, Long.class, running.merchantId(), datasetId, family);
            failures.put(family, count == null ? 0 : count);
        }
        Long simulationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM risk_simulation_results
                 WHERE merchant_id = ? AND simulation_run_id = ?
                """, Long.class, running.merchantId(), running.boundary().simulationRunId());
        long expectedSimulationCount = running.processedCount()
                - runningCount(datasetId, "synthetic_excluded_count");
        if (simulationCount == null || simulationCount != expectedSimulationCount) {
            failures.compute("POPULATION", (family, count) -> (count == null ? 0 : count)
                    + Math.abs((simulationCount == null ? 0 : simulationCount) - expectedSimulationCount));
        }
        List<FamilyResult> families = failures.entrySet().stream()
                .map(entry -> new FamilyResult(entry.getKey(),
                        entry.getValue() == 0 ? "PASSED" : "FAILED", entry.getValue()))
                .toList();
        boolean passed = families.stream().allMatch(family -> "PASSED".equals(family.status()));
        String digest = compatibilityDigest(families);
        return new CompatibilitySummary(passed ? ReplayDatasetCompatibilityStatus.PASSED
                : ReplayDatasetCompatibilityStatus.FAILED,
                passed ? null : "EQUIVALENCE_MISMATCH", digest, families);
    }

    private long runningCount(UUID datasetId, String column) {
        if (!List.of("synthetic_excluded_count", "corrupt_lineage_count").contains(column)) {
            throw new IllegalArgumentException("Unsupported replay dataset count column");
        }
        Long value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM replay_dataset_manifests WHERE id = ?",
                Long.class, datasetId);
        return value == null ? 0 : value;
    }

    private String compatibilityDigest(List<FamilyResult> families) {
        return digestCodec.digest(Map.of("families", families.stream()
                .map(family -> family.family() + ":" + family.status()
                        + ":" + family.failureCount())
                .toList()));
    }

    private void insertFinalResult(UUID merchantId, UUID datasetId,
            ReplayDatasetMetricCode metricCode, String bucketCode, long populationCount,
            Long amountMinor, Instant createdAt) {
        String resultDigest = aggregateDigest(new AggregateKey(metricCode, bucketCode),
                populationCount, amountMinor);
        jdbcTemplate.update("""
                INSERT INTO replay_dataset_results
                    (id, merchant_id, replay_dataset_id, metric_code, bucket_code,
                     population_count, amount_minor, result_sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), merchantId, datasetId, metricCode.name(), bucketCode,
                populationCount, amountMinor, resultDigest, Timestamp.from(createdAt));
    }

    private String aggregateDigest(AggregateKey key, long count, Long amount) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("amountMinor", amount);
        evidence.put("bucketCode", key.bucketCode());
        evidence.put("metricCode", key.metricCode().name());
        evidence.put("populationCount", count);
        return digestCodec.digest(evidence);
    }

    private boolean leaseActive(Timestamp timestamp) {
        return timestamp != null && timestamp.toInstant().isAfter(now());
    }

    private Instant now() {
        return EventTimePrecision.toPostgreSqlMicroseconds(clock.instant());
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public enum WorkOutcome {
        CONTINUE,
        COMPLETE,
        STALE
    }

    public enum ReplayDatasetFailureCode {
        SOURCE_DIGEST_DRIFT,
        UNSUPPORTED_WORKER_SCHEMA,
        DATASET_PROCESSING_FAILED,
        POPULATION_LIMIT_EXCEEDED
    }

    public record WorkerClaim(UUID datasetId, String workerId, UUID leaseToken,
            long fencingEpoch) { }

    private record ClaimableDataset(ReplayDatasetLifecycleStatus status,
            Instant leaseExpiresAt, long fencingEpoch) { }

    private record RunningDataset(UUID merchantId, UUID cutoffManifestId,
            UUID generationId, String eventCutoffDigest, String bootstrapDigest,
            String currentEventCutoffDigest, String currentBootstrapDigest,
            String generationSourceDigest, String workerSchemaVersion,
            String featureCatalogueVersion, String evaluatorVersion,
            long checkpointOrdinal, long processedCount, DatasetBoundary boundary) { }

    private record AggregateKey(ReplayDatasetMetricCode metricCode, String bucketCode)
            implements Comparable<AggregateKey> {
        @Override
        public int compareTo(AggregateKey other) {
            int metric = metricCode.name().compareTo(other.metricCode.name());
            return metric != 0 ? metric : bucketCode.compareTo(other.bucketCode);
        }
    }

    private record AggregateValue(long count, Long amountMinor) { }

    private record ChunkSummary(long includedCount, long unlabeledCount,
            long contradictoryCount, long supersededCount, long syntheticExcludedCount,
            long unsupportedVersionCount, long missingFeatureCount,
            long corruptLineageCount, String populationDigest,
            Map<AggregateKey, AggregateValue> aggregates) { }

    private record FinalAggregate(ReplayDatasetMetricCode metricCode, String bucketCode,
            long populationCount, Long amountMinor) { }

    private record FamilyResult(String family, String status, long failureCount) { }

    private record CompatibilitySummary(ReplayDatasetCompatibilityStatus status,
            String failureCode, String digest, List<FamilyResult> families) { }

    public static final class ReplayDatasetSourceDriftException extends RuntimeException {
        public ReplayDatasetSourceDriftException() {
            super("Replay dataset immutable source lineage changed");
        }
    }

    public static final class StaleReplayDatasetWorkerException extends RuntimeException {
        public StaleReplayDatasetWorkerException() {
            super("Replay dataset worker lost its fencing lease");
        }
    }

    public static final class ReplayDatasetPopulationLimitException extends RuntimeException {
        public ReplayDatasetPopulationLimitException(long maximumPopulation) {
            super("Replay dataset exceeds the configured population limit of "
                    + maximumPopulation);
        }
    }
}
