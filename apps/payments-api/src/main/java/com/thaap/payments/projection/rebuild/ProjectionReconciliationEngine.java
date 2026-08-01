package com.thaap.payments.projection.rebuild;

import com.thaap.payments.projection.ProjectionNames;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProjectionReconciliationEngine {

    private static final int PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final ProjectionRebuildApplier applier;
    private final ProjectionRebuildStore store;
    private final ProjectionRebuildMetrics metrics;
    private final ProjectionBootstrapSourceReader bootstrapSourceReader;
    private final Clock clock;

    public ProjectionReconciliationEngine(JdbcTemplate jdbcTemplate,
            ProjectionRebuildApplier applier, ProjectionRebuildStore store,
            ProjectionRebuildMetrics metrics,
            ProjectionBootstrapSourceReader bootstrapSourceReader, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.applier = applier;
        this.store = store;
        this.metrics = metrics;
        this.bootstrapSourceReader = bootstrapSourceReader;
        this.clock = clock;
    }

    public ReconciliationResult catchUpAndReconcile(ProjectionRebuildTarget target,
            UUID actorOperatorId) {
        SourceSnapshot source = captureSourceSnapshot();
        CatchUpResult catchUp = catchUp(target, source, actorOperatorId);
        if (!catchUp.complete()) {
            return new ReconciliationResult(target.currentReconciliationAttempt(), false,
                    source.canonicalSha256(), List.of(), false,
                    catchUp.appliedInputCount(), catchUp.remainingInputCount());
        }
        int attempt = target.currentReconciliationAttempt() + 1;
        List<InvariantResult> results = evaluate(target, source);
        Instant now = now();
        for (InvariantResult result : results) {
            jdbcTemplate.update("""
                    INSERT INTO projection_reconciliations (
                        id, projection_rebuild_id, invariant_family, result_status,
                        expected_count, actual_count, expected_sha256, actual_sha256,
                        safe_detail_code, reconciled_at, reconciliation_attempt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), target.rebuildId(), result.family().name(),
                    result.passed() ? "PASS" : "FAIL", result.expectedCount(),
                    result.actualCount(), result.expectedSha256(), result.actualSha256(),
                    result.passed() ? null : result.safeDetailCode(), Timestamp.from(now),
                    attempt);
            if (!result.passed()) {
                metrics.reconciliationMismatch(result.family());
            }
        }
        boolean passed = results.stream().allMatch(InvariantResult::passed);
        jdbcTemplate.update("""
                UPDATE projection_rebuilds
                SET current_reconciliation_attempt = ?,
                    reconciliation_source_sha256 = ?, updated_at = ?
                WHERE id = ? AND lifecycle_status IN
                    ('CATCHING_UP', 'RECONCILED', 'ACTIVATED')
                """, attempt, source.canonicalSha256(), Timestamp.from(now),
                target.rebuildId());
        if (passed && "CATCHING_UP".equals(target.lifecycleStatus())) {
            int generationChanged = jdbcTemplate.update("""
                    UPDATE projection_generations
                    SET lifecycle_status = 'RECONCILED', reconciled_at = ?
                    WHERE id = ? AND projection_definition_id = ?
                      AND lifecycle_status = 'CATCHING_UP'
                    """, Timestamp.from(now), target.projectionGenerationId(),
                    target.projectionDefinitionId());
            int rebuildChanged = jdbcTemplate.update("""
                    UPDATE projection_rebuilds
                    SET lifecycle_status = 'RECONCILED', lease_owner = NULL,
                        lease_token = NULL, lease_expires_at = NULL, updated_at = ?
                    WHERE id = ? AND lifecycle_status = 'CATCHING_UP'
                    """, Timestamp.from(now), target.rebuildId());
            if (generationChanged != 1 || rebuildChanged != 1) {
                throw new ProjectionRebuildFenceException();
            }
            store.appendEvent(target.rebuildId(), ProjectionRebuildEventType.RECONCILED,
                    target.fencingEpoch(), actorOperatorId, null,
                    target.processedInputCount(), now);
        }
        return new ReconciliationResult(attempt, passed, source.canonicalSha256(),
                results.stream().filter(result -> !result.passed())
                        .map(InvariantResult::family).toList(), true,
                catchUp.appliedInputCount(), catchUp.remainingInputCount());
    }

    public String currentSourceSha256() {
        return captureSourceSnapshot().canonicalSha256();
    }

    private CatchUpResult catchUp(ProjectionRebuildTarget target, SourceSnapshot source,
            UUID actorOperatorId) {
        ProjectionRebuildLease boundary = target.asApplicationBoundary();
        List<ProjectionRebuildInput> applied = new ArrayList<>();
        for (SourceStream stream : source.streams()) {
            if (applied.size() == PAGE_SIZE) {
                break;
            }
            Long position = jdbcTemplate.query("""
                    SELECT last_stream_version FROM projection_stream_positions
                    WHERE merchant_id = ? AND projection_generation_id = ?
                      AND stream_type = ? AND stream_id = ?
                    """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                    stream.merchantId(), target.projectionGenerationId(),
                    stream.streamType(), stream.streamId());
            long lastVersion = position == null ? 0 : position;
            if (lastVersion > stream.maximumStreamVersion()) {
                throw new IllegalStateException("Shadow projection is ahead of source truth");
            }
            if (lastVersion >= stream.maximumStreamVersion()) {
                continue;
            }
            int remainingCapacity = PAGE_SIZE - applied.size();
            List<ProjectionRebuildInput> page = loadEvents(stream, lastVersion,
                    remainingCapacity);
            if (page.isEmpty()) {
                throw new IllegalStateException("Source stream contains a version gap");
            }
            for (ProjectionRebuildInput input : page) {
                if (input.streamVersion() != lastVersion + 1) {
                    throw new IllegalStateException("Source stream contains a version gap");
                }
                applier.apply(input, boundary);
                applied.add(input);
                lastVersion = input.streamVersion();
            }
        }
        long remaining = missingInputCount(target, source);
        recordCatchUpCycle(target, source, actorOperatorId, applied, remaining);
        return new CatchUpResult(remaining == 0, applied.size(), remaining);
    }

    private List<ProjectionRebuildInput> loadEvents(SourceStream stream, long afterVersion,
            int limit) {
        return jdbcTemplate.query("""
                SELECT id, merchant_id, stream_type, stream_id, stream_version,
                       event_record_bytes, event_record_sha256, recorded_at
                FROM domain_events
                WHERE merchant_id = ? AND stream_type = ? AND stream_id = ?
                  AND stream_version > ? AND stream_version <= ?
                ORDER BY stream_version
                LIMIT ?
                """, (resultSet, rowNumber) -> ProjectionRebuildInput.event(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getString(3),
                        resultSet.getObject(4, UUID.class), resultSet.getLong(5),
                        resultSet.getBytes(6), resultSet.getString(7),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 8)),
                stream.merchantId(),
                stream.streamType(), stream.streamId(), afterVersion,
                stream.maximumStreamVersion(), limit);
    }

    private long missingInputCount(ProjectionRebuildTarget target, SourceSnapshot source) {
        long missing = 0;
        for (SourceStream stream : source.streams()) {
            Long position = jdbcTemplate.query("""
                    SELECT last_stream_version FROM projection_stream_positions
                    WHERE merchant_id = ? AND projection_generation_id = ?
                      AND stream_type = ? AND stream_id = ?
                    """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                    stream.merchantId(), target.projectionGenerationId(),
                    stream.streamType(), stream.streamId());
            long lastVersion = position == null ? 0 : position;
            if (lastVersion > stream.maximumStreamVersion()) {
                throw new IllegalStateException("Shadow projection is ahead of source truth");
            }
            missing = Math.addExact(missing, stream.maximumStreamVersion() - lastVersion);
        }
        return missing;
    }

    private void recordCatchUpCycle(ProjectionRebuildTarget target, SourceSnapshot source,
            UUID actorOperatorId, List<ProjectionRebuildInput> applied, long remaining) {
        int cycleNumber = Math.addExact(target.catchupCycleCount(), 1);
        Instant occurredAt = now();
        UUID firstEventId = applied.isEmpty() ? null : applied.getFirst().inputId();
        UUID lastEventId = applied.isEmpty() ? null : applied.getLast().inputId();
        int changed = jdbcTemplate.update("""
                UPDATE projection_rebuilds
                SET catchup_cycle_count = ?,
                    catchup_input_count = catchup_input_count + ?,
                    catchup_remaining_count = ?, latest_catchup_source_sha256 = ?,
                    updated_at = ?
                WHERE id = ? AND catchup_cycle_count = ?
                  AND lifecycle_status IN ('CATCHING_UP', 'RECONCILED', 'ACTIVATED')
                """, cycleNumber, applied.size(), remaining, source.canonicalSha256(),
                Timestamp.from(occurredAt), target.rebuildId(), target.catchupCycleCount());
        if (changed != 1) {
            throw new ProjectionRebuildFenceException();
        }
        int inserted = jdbcTemplate.update("""
                INSERT INTO projection_catchup_cycles (
                    id, projection_rebuild_id, cycle_number, source_snapshot_sha256,
                    source_event_count, applied_input_count, remaining_input_count,
                    first_domain_event_id, last_domain_event_id,
                    actor_operator_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), target.rebuildId(), cycleNumber,
                source.canonicalSha256(), source.eventCount(), applied.size(), remaining,
                firstEventId, lastEventId, actorOperatorId, Timestamp.from(occurredAt));
        if (inserted != 1) {
            throw new IllegalStateException("Projection catch-up evidence was not singular");
        }
    }

    private List<InvariantResult> evaluate(ProjectionRebuildTarget target,
            SourceSnapshot source) {
        List<InvariantResult> results = new ArrayList<>();
        Long actualInputCount = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(last_stream_version), 0)
                FROM projection_stream_positions
                WHERE projection_generation_id = ?
                """, Long.class, target.projectionGenerationId());
        results.add(InvariantResult.count(ProjectionReconciliationFamily.INPUT_COUNT,
                source.eventCount(), value(actualInputCount), "INPUT_COUNT_MISMATCH"));

        List<String> actualStreams = jdbcTemplate.query("""
                SELECT merchant_id, stream_type, stream_id, last_stream_version,
                       last_domain_event_id, last_event_record_sha256
                FROM projection_stream_positions
                WHERE projection_generation_id = ?
                ORDER BY merchant_id, stream_type, stream_id
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class) + "|"
                        + resultSet.getString(2) + "|"
                        + resultSet.getObject(3, UUID.class) + "|"
                        + resultSet.getLong(4) + "|"
                        + resultSet.getObject(5, UUID.class) + "|"
                        + resultSet.getString(6), target.projectionGenerationId());
        String actualStreamDigest = digestLines(actualStreams);
        results.add(InvariantResult.digestAndCount(
                ProjectionReconciliationFamily.STREAM_COVERAGE,
                source.streams().size(), actualStreams.size(), source.streamDigestSha256(),
                actualStreamDigest, "STREAM_COVERAGE_MISMATCH"));

        UUID activeGenerationId = jdbcTemplate.queryForObject("""
                SELECT active_generation_id FROM projection_definitions WHERE id = ?
                """, UUID.class, target.projectionDefinitionId());
        long expectedRows = rowCount(target.projectionName(), activeGenerationId);
        long actualRows = rowCount(target.projectionName(), target.projectionGenerationId());
        long bootstrapFactCount = bootstrapFacts(target).size();
        long activeBootstrapCount = activeBootstrapCount(target.projectionName(),
                activeGenerationId);
        boolean bootstrappingLegacyTruth = bootstrapFactCount > 0
                && activeBootstrapCount == 0;
        if (bootstrappingLegacyTruth) {
            expectedRows = actualRows;
        }
        results.add(InvariantResult.count(
                ProjectionReconciliationFamily.PROJECTION_ROW_COUNT,
                expectedRows, actualRows, "PROJECTION_ROW_COUNT_MISMATCH"));

        String expectedContent = contentDigest(target.projectionName(), activeGenerationId);
        String actualContent = contentDigest(target.projectionName(),
                target.projectionGenerationId());
        if (bootstrappingLegacyTruth) {
            expectedContent = actualContent;
        }
        results.add(InvariantResult.digest(ProjectionReconciliationFamily.CONTENT_DIGEST,
                expectedContent, actualContent, "CONTENT_DIGEST_MISMATCH"));

        long violations = Math.addExact(businessInvariantViolations(target.projectionName(),
                target.projectionGenerationId()), bootstrapLineageViolations(target,
                        activeGenerationId));
        results.add(InvariantResult.count(ProjectionReconciliationFamily.BUSINESS_INVARIANT,
                0, violations, "BUSINESS_INVARIANT_MISMATCH"));
        return results;
    }

    private SourceSnapshot captureSourceSnapshot() {
        List<SourceStream> streams = jdbcTemplate.query("""
                SELECT head.merchant_id, head.stream_type, head.stream_id,
                       head.current_version, event.id, event.event_record_sha256
                FROM event_stream_heads head
                JOIN domain_events event
                  ON event.merchant_id = head.merchant_id
                 AND event.stream_type = head.stream_type
                 AND event.stream_id = head.stream_id
                 AND event.stream_version = head.current_version
                WHERE head.current_version > 0
                ORDER BY head.merchant_id, head.stream_type, head.stream_id
                """, (resultSet, rowNumber) -> new SourceStream(
                        resultSet.getObject(1, UUID.class), resultSet.getString(2),
                        resultSet.getObject(3, UUID.class), resultSet.getLong(4),
                        resultSet.getObject(5, UUID.class), resultSet.getString(6)));
        List<String> lines = streams.stream().map(SourceStream::canonicalLine).toList();
        long eventCount = streams.stream().mapToLong(SourceStream::maximumStreamVersion).sum();
        String streamDigest = digestLines(lines);
        return new SourceSnapshot(streams, eventCount, streamDigest,
                ProjectionCutoffManifestService.sha256("source=" + streamDigest
                        + "\nevents=" + eventCount + "\n"));
    }

    private long rowCount(String projectionName, UUID generationId) {
        String table = projectionTable(projectionName);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE projection_generation_id = ?", Long.class, generationId);
        return value(count);
    }

    private String contentDigest(String projectionName, UUID generationId) {
        List<String> rows;
        if (ProjectionNames.PAYMENT_RISK.equals(projectionName)) {
            rows = jdbcTemplate.query("""
                    SELECT merchant_id, payment_intent_id, intent_amount_minor, currency,
                           capture_method, traffic_origin, attempt_number, attempt_outcome,
                           processor_code, fraud_action, risk_level, risk_score,
                           reason_codes_json, ruleset_version, feature_catalogue_version,
                           fraud_decision_id, feature_snapshot_id,
                           review_status, review_outcome, captured_amount_minor,
                           refunded_amount_minor, disputed_amount_minor, label_value,
                           fraud_type, label_source, label_available_at, lifecycle_status,
                           coverage_status, last_stream_version, last_event_record_sha256
                    FROM payment_risk_projections
                    WHERE projection_generation_id = ?
                    ORDER BY merchant_id, payment_intent_id
                    """, (resultSet, rowNumber) -> joinColumns(resultSet, 30), generationId);
        } else {
            rows = jdbcTemplate.query("""
                    SELECT merchant_id, interval_type, interval_start,
                           traffic_origin_policy, currency, payment_count, attempt_count,
                           authorization_count, decline_count, review_count,
                           confirmed_fraud_count, chargeback_count,
                           enumeration_signal_count, unlabeled_count,
                           unknown_outcome_count, intent_amount_minor,
                           authorized_amount_minor, captured_amount_minor,
                           refunded_amount_minor, disputed_amount_minor, source_event_count,
                           coverage_status
                    FROM merchant_risk_aggregates
                    WHERE projection_generation_id = ?
                    ORDER BY merchant_id, interval_type, interval_start,
                             traffic_origin_policy, currency
                    """, (resultSet, rowNumber) -> joinColumns(resultSet, 22), generationId);
        }
        return digestLines(rows);
    }

    private long businessInvariantViolations(String projectionName, UUID generationId) {
        String sql;
        if (ProjectionNames.PAYMENT_RISK.equals(projectionName)) {
            sql = """
                    SELECT COUNT(*) FROM payment_risk_projections
                    WHERE projection_generation_id = ?
                      AND (captured_amount_minor < 0 OR refunded_amount_minor < 0
                           OR disputed_amount_minor < 0
                           OR (last_stream_version <= 0
                               AND source_bootstrap_fact_id IS NULL)
                           OR coverage_status NOT IN ('COMPLETE', 'PARTIAL'))
                    """;
        } else {
            sql = """
                    SELECT COUNT(*) FROM merchant_risk_aggregates
                    WHERE projection_generation_id = ?
                      AND (payment_count < 0 OR attempt_count < 0
                           OR authorization_count < 0 OR decline_count < 0
                           OR review_count < 0 OR confirmed_fraud_count < 0
                           OR chargeback_count < 0 OR enumeration_signal_count < 0
                           OR unlabeled_count < 0 OR unknown_outcome_count < 0
                           OR intent_amount_minor < 0 OR authorized_amount_minor < 0
                           OR captured_amount_minor < 0 OR refunded_amount_minor < 0
                           OR disputed_amount_minor < 0
                           OR source_event_count + source_bootstrap_fact_count <= 0)
                    """;
        }
        Long count = jdbcTemplate.queryForObject(sql, Long.class, generationId);
        return value(count);
    }

    private long activeBootstrapCount(String projectionName, UUID generationId) {
        String sql = ProjectionNames.PAYMENT_RISK.equals(projectionName)
                ? "SELECT COUNT(*) FROM payment_risk_projections "
                        + "WHERE projection_generation_id = ? "
                        + "AND source_bootstrap_fact_id IS NOT NULL"
                : "SELECT COALESCE(SUM(source_bootstrap_fact_count), 0) "
                        + "FROM merchant_risk_aggregates "
                        + "WHERE projection_generation_id = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, generationId);
        return value(count);
    }

    private List<BootstrapInput> bootstrapFacts(ProjectionRebuildTarget target) {
        return jdbcTemplate.query("""
                SELECT fact.id, fact.canonical_fact_bytes, fact.canonical_fact_sha256
                FROM projection_cutoff_manifests cutoff
                JOIN projection_bootstrap_facts fact
                  ON fact.bootstrap_manifest_id = cutoff.bootstrap_manifest_id
                WHERE cutoff.id = ?
                ORDER BY fact.merchant_id, fact.source_id
                """, (resultSet, rowNumber) -> new BootstrapInput(
                        resultSet.getObject(1, UUID.class), resultSet.getBytes(2),
                        resultSet.getString(3)), target.cutoffManifestId());
    }

    private long bootstrapLineageViolations(ProjectionRebuildTarget target,
            UUID activeGenerationId) {
        List<BootstrapInput> inputs = bootstrapFacts(target);
        if (inputs.isEmpty()) {
            return 0;
        }
        long violations = 0;
        List<ProjectionBootstrapPaymentFact> decodedFacts = new ArrayList<>();
        for (BootstrapInput input : inputs) {
            if (!ProjectionCutoffManifestService.sha256(input.canonicalBytes())
                    .equals(input.canonicalSha256())) {
                violations++;
                continue;
            }
            ProjectionBootstrapPaymentFact fact = bootstrapSourceReader.decode(
                    input.canonicalBytes());
            decodedFacts.add(fact);
            violations = Math.addExact(violations,
                    ProjectionNames.PAYMENT_RISK.equals(target.projectionName())
                            ? paymentBootstrapViolations(target, input.inputId(), fact)
                            : merchantBootstrapCohortViolations(target, input.inputId(), fact));
        }
        if (ProjectionNames.MERCHANT_RISK.equals(target.projectionName())
                && activeBootstrapCount(target.projectionName(), activeGenerationId) == 0) {
            violations = Math.addExact(violations,
                    merchantBootstrapAggregateViolations(target, decodedFacts));
        }
        return violations;
    }

    private long merchantBootstrapAggregateViolations(ProjectionRebuildTarget target,
            List<ProjectionBootstrapPaymentFact> facts) {
        if (facts.size() != bootstrapFacts(target).size() || hasPostCutoffEvents(facts)) {
            return 0;
        }
        Map<MerchantAggregateKey, MerchantAggregateValues> expected = new LinkedHashMap<>();
        for (ProjectionBootstrapPaymentFact fact : facts) {
            Instant hour = fact.intentOccurredAt().truncatedTo(ChronoUnit.HOURS);
            Instant day = fact.intentOccurredAt().truncatedTo(ChronoUnit.DAYS);
            addExpectedAggregate(expected, fact, "HOUR", hour, "ALL");
            addExpectedAggregate(expected, fact, "DAY", day, "ALL");
            String originPolicy = "SYNTHETIC_SCENARIO".equals(fact.trafficOrigin())
                    ? "SYNTHETIC_ONLY"
                    : "MERCHANT_API".equals(fact.trafficOrigin()) ? "LIVE_ONLY" : null;
            if (originPolicy != null) {
                addExpectedAggregate(expected, fact, "HOUR", hour, originPolicy);
                addExpectedAggregate(expected, fact, "DAY", day, originPolicy);
            }
        }
        Map<MerchantAggregateKey, MerchantAggregateValues> actual = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT merchant_id, interval_type, interval_start,
                       traffic_origin_policy, currency, payment_count, attempt_count,
                       authorization_count, decline_count, review_count,
                       confirmed_fraud_count, chargeback_count,
                       enumeration_signal_count, unlabeled_count,
                       unknown_outcome_count, intent_amount_minor,
                       authorized_amount_minor, captured_amount_minor,
                       refunded_amount_minor, disputed_amount_minor, source_event_count,
                       source_bootstrap_fact_count
                FROM merchant_risk_aggregates
                WHERE projection_generation_id = ?
                """, resultSet -> {
                    MerchantAggregateKey key = new MerchantAggregateKey(
                            resultSet.getObject(1, UUID.class), resultSet.getString(2),
                            com.thaap.payments.persistence.JdbcTime.instant(resultSet, 3),
                            resultSet.getString(4), resultSet.getString(5));
                    actual.put(key, new MerchantAggregateValues(
                            resultSet.getLong(6), resultSet.getLong(7),
                            resultSet.getLong(8), resultSet.getLong(9),
                            resultSet.getLong(10), resultSet.getLong(11),
                            resultSet.getLong(12), resultSet.getLong(13),
                            resultSet.getLong(14), resultSet.getLong(15),
                            resultSet.getLong(16), resultSet.getLong(17),
                            resultSet.getLong(18), resultSet.getLong(19),
                            resultSet.getLong(20), resultSet.getLong(21),
                            resultSet.getLong(22)));
                }, target.projectionGenerationId());
        return expected.equals(actual) ? 0 : 1;
    }

    private boolean hasPostCutoffEvents(List<ProjectionBootstrapPaymentFact> facts) {
        for (ProjectionBootstrapPaymentFact fact : facts) {
            Long count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM domain_events
                    WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                      AND stream_version > ?
                    """, Long.class, fact.merchantId(), fact.paymentIntentId(),
                    fact.baseStreamVersion());
            if (value(count) > 0) {
                return true;
            }
        }
        return false;
    }

    private void addExpectedAggregate(
            Map<MerchantAggregateKey, MerchantAggregateValues> expected,
            ProjectionBootstrapPaymentFact fact, String intervalType,
            Instant intervalStart, String originPolicy) {
        MerchantAggregateKey key = new MerchantAggregateKey(fact.merchantId(), intervalType,
                intervalStart, originPolicy, fact.currency());
        expected.merge(key, MerchantAggregateValues.from(fact),
                MerchantAggregateValues::plus);
    }

    private long paymentBootstrapViolations(ProjectionRebuildTarget target,
            UUID inputId, ProjectionBootstrapPaymentFact fact) {
        List<BootstrapPaymentState> states = jdbcTemplate.query("""
                SELECT merchant_id, payment_intent_id, intent_amount_minor, currency,
                       capture_method, traffic_origin, attempt_number, attempt_outcome,
                       processor_code, fraud_action, risk_level, risk_score,
                       reason_codes_json, ruleset_version, feature_catalogue_version,
                       fraud_decision_id, feature_snapshot_id,
                       review_status, review_outcome, captured_amount_minor,
                       refunded_amount_minor, disputed_amount_minor, label_value,
                       fraud_type, label_source, label_available_at, lifecycle_status,
                       coverage_status, intent_occurred_at, last_stream_version,
                       last_domain_event_id, last_event_record_sha256,
                       source_bootstrap_fact_id
                FROM payment_risk_projections
                WHERE projection_generation_id = ? AND source_bootstrap_fact_id = ?
                """, (resultSet, rowNumber) -> new BootstrapPaymentState(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getLong(3),
                        resultSet.getString(4), resultSet.getString(5), resultSet.getString(6),
                        resultSet.getObject(7, Integer.class), resultSet.getString(8),
                        resultSet.getString(9), resultSet.getString(10), resultSet.getString(11),
                        resultSet.getObject(12, Integer.class), resultSet.getString(13),
                        resultSet.getString(14), resultSet.getString(15),
                        resultSet.getObject(16, UUID.class), resultSet.getObject(17, UUID.class),
                        resultSet.getString(18), resultSet.getString(19),
                        resultSet.getLong(20), resultSet.getLong(21),
                        resultSet.getLong(22), resultSet.getString(23),
                        resultSet.getString(24), resultSet.getString(25),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 26),
                        resultSet.getString(27), resultSet.getString(28),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 29),
                        resultSet.getLong(30), resultSet.getObject(31, UUID.class),
                        resultSet.getString(32), resultSet.getObject(33, UUID.class)),
                target.projectionGenerationId(), inputId);
        if (states.size() != 1) {
            return 1;
        }
        BootstrapPaymentState state = states.getFirst();
        boolean matches = state.merchantId().equals(fact.merchantId())
                && state.paymentIntentId().equals(fact.paymentIntentId())
                && state.intentAmountMinor() == fact.intentAmountMinor()
                && Objects.equals(state.currency(), fact.currency())
                && Objects.equals(state.captureMethod(), fact.captureMethod())
                && Objects.equals(state.trafficOrigin(), fact.trafficOrigin())
                && Objects.equals(state.attemptNumber(), fact.attemptNumber())
                && Objects.equals(state.attemptOutcome(), fact.attemptOutcome())
                && Objects.equals(state.processorCode(), fact.processorCode())
                && Objects.equals(state.fraudAction(), fact.fraudAction())
                && Objects.equals(state.riskLevel(), fact.riskLevel())
                && Objects.equals(state.riskScore(), fact.riskScore())
                && Objects.equals(state.reasonCodesJson(), fact.reasonCodesJson())
                && Objects.equals(state.rulesetVersion(), fact.rulesetVersion())
                && Objects.equals(state.featureCatalogueVersion(),
                        fact.featureCatalogueVersion())
                && Objects.equals(state.fraudDecisionId(), fact.fraudDecisionId())
                && Objects.equals(state.featureSnapshotId(), fact.featureSnapshotId())
                && Objects.equals(state.reviewStatus(), fact.reviewStatus())
                && Objects.equals(state.reviewOutcome(), fact.reviewOutcome())
                && state.capturedAmountMinor() == fact.capturedAmountMinor()
                && state.refundedAmountMinor() == fact.refundedAmountMinor()
                && state.disputedAmountMinor() == fact.disputedAmountMinor()
                && Objects.equals(state.labelValue(), fact.labelValue())
                && Objects.equals(state.fraudType(), fact.fraudType())
                && Objects.equals(state.labelSource(), fact.labelSource())
                && Objects.equals(state.labelAvailableAt(), fact.labelAvailableAt())
                && Objects.equals(state.lifecycleStatus(), fact.lifecycleStatus())
                && "PARTIAL".equals(state.coverageStatus())
                && Objects.equals(state.intentOccurredAt(), fact.intentOccurredAt())
                && state.lastStreamVersion() == fact.baseStreamVersion()
                && Objects.equals(state.lastDomainEventId(), fact.baseTerminalDomainEventId())
                && Objects.equals(state.lastEventRecordSha256(),
                        fact.baseTerminalEventRecordSha256())
                && state.sourceBootstrapFactId().equals(inputId);
        return matches ? 0 : 1;
    }

    private long merchantBootstrapCohortViolations(ProjectionRebuildTarget target,
            UUID inputId, ProjectionBootstrapPaymentFact fact) {
        Integer matches = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM merchant_payment_cohort_states
                WHERE projection_generation_id = ? AND source_bootstrap_fact_id = ?
                  AND merchant_id = ? AND payment_intent_id = ? AND currency = ?
                  AND traffic_origin = ? AND intent_occurred_at = ?
                  AND last_stream_version = ?
                  AND last_domain_event_id IS NOT DISTINCT FROM ?
                  AND last_event_record_sha256 IS NOT DISTINCT FROM ?
                """, Integer.class, target.projectionGenerationId(), inputId,
                fact.merchantId(), fact.paymentIntentId(), fact.currency(),
                fact.trafficOrigin(), Timestamp.from(fact.intentOccurredAt()),
                fact.baseStreamVersion(), fact.baseTerminalDomainEventId(),
                fact.baseTerminalEventRecordSha256());
        return matches != null && matches == 1 ? 0 : 1;
    }

    private String projectionTable(String projectionName) {
        if (ProjectionNames.PAYMENT_RISK.equals(projectionName)) {
            return "payment_risk_projections";
        }
        if (ProjectionNames.MERCHANT_RISK.equals(projectionName)) {
            return "merchant_risk_aggregates";
        }
        throw new IllegalArgumentException("Projection name is unsupported");
    }

    private String joinColumns(java.sql.ResultSet resultSet, int count)
            throws java.sql.SQLException {
        StringBuilder value = new StringBuilder();
        for (int index = 1; index <= count; index++) {
            if (index > 1) {
                value.append('|');
            }
            value.append(Objects.toString(resultSet.getObject(index), "<null>"));
        }
        return value.toString();
    }

    private String digestLines(List<String> lines) {
        StringBuilder canonical = new StringBuilder();
        for (String line : lines) {
            canonical.append(line).append('\n');
        }
        return ProjectionCutoffManifestService.sha256(canonical.toString());
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record ReconciliationResult(int attempt, boolean passed,
            String sourceSha256, List<ProjectionReconciliationFamily> mismatchFamilies,
            boolean catchUpComplete, int catchUpAppliedInputCount,
            long catchUpRemainingInputCount) {
    }

    private record CatchUpResult(boolean complete, int appliedInputCount,
            long remainingInputCount) {
    }

    private record BootstrapInput(UUID inputId, byte[] canonicalBytes,
            String canonicalSha256) {
    }

    private record BootstrapPaymentState(UUID merchantId, UUID paymentIntentId,
            long intentAmountMinor, String currency, String captureMethod,
            String trafficOrigin, Integer attemptNumber, String attemptOutcome,
            String processorCode, String fraudAction, String riskLevel, Integer riskScore,
            String reasonCodesJson, String rulesetVersion, String featureCatalogueVersion,
            UUID fraudDecisionId, UUID featureSnapshotId,
            String reviewStatus, String reviewOutcome, long capturedAmountMinor,
            long refundedAmountMinor, long disputedAmountMinor, String labelValue,
            String fraudType, String labelSource, Instant labelAvailableAt,
            String lifecycleStatus, String coverageStatus, Instant intentOccurredAt,
            long lastStreamVersion, UUID lastDomainEventId, String lastEventRecordSha256,
            UUID sourceBootstrapFactId) {
    }

    private record MerchantAggregateKey(UUID merchantId, String intervalType,
            Instant intervalStart, String originPolicy, String currency) {
    }

    private record MerchantAggregateValues(long paymentCount, long attemptCount,
            long authorizationCount, long declineCount, long reviewCount,
            long confirmedFraudCount, long chargebackCount, long enumerationSignalCount,
            long unlabeledCount, long unknownOutcomeCount, long intentAmountMinor,
            long authorizedAmountMinor, long capturedAmountMinor, long refundedAmountMinor,
            long disputedAmountMinor, long sourceEventCount, long sourceBootstrapFactCount) {

        private static MerchantAggregateValues from(ProjectionBootstrapPaymentFact fact) {
            return new MerchantAggregateValues(1, fact.attemptCount(),
                    fact.authorizationCount(), fact.declineCount(), fact.reviewCount(),
                    "FRAUD".equals(fact.labelValue()) ? 1 : 0, fact.chargebackCount(), 0,
                    fact.labelValue() == null ? 1 : 0, fact.unknownOutcomeCount(),
                    fact.intentAmountMinor(), fact.authorizedAmountMinor(),
                    fact.capturedAmountMinor(), fact.refundedAmountMinor(),
                    fact.disputedAmountMinor(), 0, 1);
        }

        private MerchantAggregateValues plus(MerchantAggregateValues other) {
            return new MerchantAggregateValues(
                    Math.addExact(paymentCount, other.paymentCount),
                    Math.addExact(attemptCount, other.attemptCount),
                    Math.addExact(authorizationCount, other.authorizationCount),
                    Math.addExact(declineCount, other.declineCount),
                    Math.addExact(reviewCount, other.reviewCount),
                    Math.addExact(confirmedFraudCount, other.confirmedFraudCount),
                    Math.addExact(chargebackCount, other.chargebackCount),
                    Math.addExact(enumerationSignalCount, other.enumerationSignalCount),
                    Math.addExact(unlabeledCount, other.unlabeledCount),
                    Math.addExact(unknownOutcomeCount, other.unknownOutcomeCount),
                    Math.addExact(intentAmountMinor, other.intentAmountMinor),
                    Math.addExact(authorizedAmountMinor, other.authorizedAmountMinor),
                    Math.addExact(capturedAmountMinor, other.capturedAmountMinor),
                    Math.addExact(refundedAmountMinor, other.refundedAmountMinor),
                    Math.addExact(disputedAmountMinor, other.disputedAmountMinor),
                    Math.addExact(sourceEventCount, other.sourceEventCount),
                    Math.addExact(sourceBootstrapFactCount,
                            other.sourceBootstrapFactCount));
        }
    }

    private record SourceSnapshot(List<SourceStream> streams, long eventCount,
            String streamDigestSha256, String canonicalSha256) {
    }

    private record SourceStream(UUID merchantId, String streamType, UUID streamId,
            long maximumStreamVersion, UUID terminalEventId, String terminalRecordSha256) {
        private String canonicalLine() {
            return merchantId + "|" + streamType + "|" + streamId + "|"
                    + maximumStreamVersion + "|" + terminalEventId + "|"
                    + terminalRecordSha256;
        }
    }

    private record InvariantResult(ProjectionReconciliationFamily family,
            boolean passed, Long expectedCount, Long actualCount,
            String expectedSha256, String actualSha256, String safeDetailCode) {

        private static InvariantResult count(ProjectionReconciliationFamily family,
                long expected, long actual, String detail) {
            return new InvariantResult(family, expected == actual, expected, actual,
                    null, null, detail);
        }

        private static InvariantResult digest(ProjectionReconciliationFamily family,
                String expected, String actual, String detail) {
            return new InvariantResult(family, Objects.equals(expected, actual),
                    null, null, expected, actual, detail);
        }

        private static InvariantResult digestAndCount(
                ProjectionReconciliationFamily family, long expectedCount,
                long actualCount, String expected, String actual, String detail) {
            return new InvariantResult(family,
                    expectedCount == actualCount && Objects.equals(expected, actual),
                    expectedCount, actualCount, expected, actual, detail);
        }
    }
}
