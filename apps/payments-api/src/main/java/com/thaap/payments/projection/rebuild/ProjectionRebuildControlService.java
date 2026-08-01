package com.thaap.payments.projection.rebuild;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.platform.PlatformOperatorAuthenticator;
import com.thaap.payments.platform.PlatformOperatorContext;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.security.SensitiveValueHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectionRebuildControlService {

    private static final String REQUEST_IDENTITY_DOMAIN =
            "platform.data.operation.request.identity";
    private static final String REQUEST_DIGEST_DOMAIN =
            "platform.data.operation.request.digest";

    private final PlatformOperatorAuthenticator authenticator;
    private final SensitiveValueHasher hasher;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final ProjectionCutoffManifestService cutoffService;
    private final ProjectionReconciliationEngine reconciliationEngine;
    private final ProjectionRebuildStore store;
    private final ProjectionRebuildMetrics metrics;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate serializableTransaction;

    public ProjectionRebuildControlService(PlatformOperatorAuthenticator authenticator,
            SensitiveValueHasher hasher, JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedJdbcTemplate,
            ProjectionCutoffManifestService cutoffService,
            ProjectionReconciliationEngine reconciliationEngine,
            ProjectionRebuildStore store, ProjectionRebuildMetrics metrics,
            AuditLogService auditLogService, ApplicationEventPublisher eventPublisher,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.authenticator = authenticator;
        this.hasher = hasher;
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.cutoffService = cutoffService;
        this.reconciliationEngine = reconciliationEngine;
        this.store = store;
        this.metrics = metrics;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.serializableTransaction = new TransactionTemplate(transactionManager);
        this.serializableTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    public RebuildView start(String rawCredential, String idempotencyKey,
            String projectionName, String trafficOriginPolicy, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        String normalizedName = requireProjectionName(projectionName);
        if (trafficOriginPolicy != null && !trafficOriginPolicy.isBlank()
                && !"ALL".equals(trafficOriginPolicy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Projection rebuild origin policy must be ALL");
        }
        RebuildView result = serializableTransaction.execute(status -> {
            PlatformOperatorContext actor = requireDataOperator(rawCredential);
            ProjectionDefinition definition = requireDefinitionForUpdate(normalizedName);
            String operation = "START_REBUILD";
            String canonicalRequest = "projection=" + normalizedName + "\norigin=ALL";
            DataOperationRequest replay = findReplay(actor, operation, idempotencyKey,
                    definition.definitionId(), canonicalRequest);
            if (replay != null) {
                return requireView(normalizedName, replay.resultId(), true);
            }
            Long open = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM projection_rebuilds
                    WHERE projection_definition_id = ?
                      AND lifecycle_status IN
                          ('QUEUED', 'RUNNING', 'CATCHING_UP', 'RECONCILED')
                    """, Long.class, definition.definitionId());
            if (open != null && open > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Projection already has an unfinished rebuild");
            }
            Instant now = now();
            ProjectionCutoffManifestService.FrozenCutoff cutoff = cutoffService.freeze(
                    definition.definitionId(), normalizedName,
                    definition.handlerVersion(), now);
            List<UUID> stableIdentity = jdbcTemplate.query("""
                    SELECT id FROM projection_rebuilds
                    WHERE projection_definition_id = ? AND cutoff_manifest_id = ?
                      AND handler_version = ?
                    """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                    definition.definitionId(), cutoff.cutoffManifestId(),
                    definition.handlerVersion());
            if (stableIdentity.size() == 1) {
                UUID priorRebuildId = stableIdentity.getFirst();
                persistRequest(actor, operation, idempotencyKey,
                        definition.definitionId(), priorRebuildId, canonicalRequest, now);
                auditLogService.recordPlatformOperatorAction(actor, requestId,
                        "projection_rebuild.reused", "projection_rebuild", priorRebuildId,
                        "Data operator reused the stable projection rebuild identity",
                        safeState(normalizedName, "PRESENT"),
                        safeState(normalizedName, "UNCHANGED"));
                metrics.afterCommit("start", "stable_identity_reused");
                return requireView(normalizedName, priorRebuildId, true);
            }
            if (!stableIdentity.isEmpty()) {
                throw new IllegalStateException("Projection rebuild identity is ambiguous");
            }
            Long maximumGeneration = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(generation_number), 0)
                    FROM projection_generations WHERE projection_definition_id = ?
                    """, Long.class, definition.definitionId());
            long generationNumber = (maximumGeneration == null ? 0 : maximumGeneration) + 1;
            UUID generationId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO projection_generations (
                        id, projection_definition_id, generation_number, handler_version,
                        build_source, lifecycle_status, source_cutoff_digest, created_at,
                        reconciled_at, activated_at, retired_at)
                    VALUES (?, ?, ?, ?, 'REBUILD', 'BUILDING', ?, ?, NULL, NULL, NULL)
                    """, generationId, definition.definitionId(), generationNumber,
                    definition.handlerVersion(), cutoff.canonicalManifestSha256(),
                    Timestamp.from(now));
            UUID rebuildId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO projection_rebuilds (
                        id, projection_definition_id, projection_generation_id,
                        cutoff_manifest_id, requested_by_operator_id, handler_version,
                        lifecycle_status, lease_owner, lease_token, lease_expires_at,
                        fencing_epoch, processed_input_count, expected_input_count,
                        last_checkpoint_key, failure_code, created_at, updated_at,
                        completed_at, current_reconciliation_attempt,
                        reconciliation_source_sha256, previous_active_generation_id)
                    VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', NULL, NULL, NULL,
                        0, 0, ?, NULL, NULL, ?, ?, NULL, 0, NULL, NULL)
                    """, rebuildId, definition.definitionId(), generationId,
                    cutoff.cutoffManifestId(), actor.operatorId(), definition.handlerVersion(),
                    cutoff.expectedInputCount(), Timestamp.from(now), Timestamp.from(now));
            store.appendEvent(rebuildId, ProjectionRebuildEventType.QUEUED, 0,
                    actor.operatorId(), null, 0, now);
            persistRequest(actor, operation, idempotencyKey, definition.definitionId(),
                    rebuildId, canonicalRequest, now);
            auditLogService.recordPlatformOperatorAction(actor, requestId,
                    "projection_rebuild.started", "projection_rebuild", rebuildId,
                    "Data operator started an isolated shadow rebuild",
                    safeState(normalizedName, "ABSENT"), safeState(normalizedName, "QUEUED"));
            metrics.afterCommit("start", "queued");
            eventPublisher.publishEvent(new ProjectionRebuildQueuedEvent(rebuildId));
            return requireView(normalizedName, rebuildId, false);
        });
        return requireResult(result);
    }

    public RebuildView status(String rawCredential, String projectionName, UUID rebuildId) {
        String normalizedName = requireProjectionName(projectionName);
        RebuildView result = transaction.execute(status -> {
            requireDataOperator(rawCredential);
            requireDefinitionForUpdate(normalizedName);
            return requireView(normalizedName, rebuildId, false);
        });
        return requireResult(result);
    }

    public RebuildView cancel(String rawCredential, String idempotencyKey,
            String projectionName, UUID rebuildId, String requestId) {
        return mutateRebuild(rawCredential, idempotencyKey, projectionName, rebuildId,
                "CANCEL_REBUILD", "cancel", requestId, (actor, definition, target) -> {
                    if (!List.of("QUEUED", "RUNNING", "CATCHING_UP")
                            .contains(target.lifecycleStatus())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Projection rebuild cannot be canceled from its current state");
                    }
                    Instant now = now();
                    int generationChanged = jdbcTemplate.update("""
                            UPDATE projection_generations
                            SET lifecycle_status = 'CANCELED'
                            WHERE id = ? AND lifecycle_status IN ('BUILDING', 'CATCHING_UP')
                            """, target.projectionGenerationId());
                    int rebuildChanged = jdbcTemplate.update("""
                            UPDATE projection_rebuilds
                            SET lifecycle_status = 'CANCELED', lease_owner = NULL,
                                lease_token = NULL, lease_expires_at = NULL,
                                updated_at = ?, completed_at = ?
                            WHERE id = ? AND lifecycle_status IN
                                ('QUEUED', 'RUNNING', 'CATCHING_UP')
                            """, Timestamp.from(now), Timestamp.from(now), target.rebuildId());
                    if (generationChanged != 1 || rebuildChanged != 1) {
                        throw new ProjectionRebuildFenceException();
                    }
                    store.appendEvent(target.rebuildId(),
                            ProjectionRebuildEventType.CANCELED, target.fencingEpoch(),
                            actor.operatorId(), "OPERATOR_REQUESTED",
                            target.processedInputCount(), now);
                    return "CANCELED";
                });
    }

    public ReconciliationView reconcile(String rawCredential, String idempotencyKey,
            String projectionName, UUID rebuildId, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        String normalizedName = requireProjectionName(projectionName);
        ReconciliationView result = transaction.execute(status -> {
            PlatformOperatorContext actor = requireDataOperator(rawCredential);
            ProjectionDefinition definition = requireDefinitionForUpdate(normalizedName);
            String operation = "RECONCILE_REBUILD";
            String canonicalRequest = "projection=" + normalizedName + "\nrebuild=" + rebuildId;
            DataOperationRequest replay = findReplay(actor, operation, idempotencyKey,
                    rebuildId, canonicalRequest);
            if (replay != null) {
                return latestReconciliation(normalizedName, rebuildId, true);
            }
            ProjectionRebuildTarget target = requireTargetForUpdate(definition, rebuildId);
            if (!"CATCHING_UP".equals(target.lifecycleStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Projection rebuild is not ready for reconciliation");
            }
            ProjectionReconciliationEngine.ReconciliationResult evidence =
                    reconciliationEngine.catchUpAndReconcile(target, actor.operatorId());
            Instant now = now();
            persistRequest(actor, operation, idempotencyKey, rebuildId, rebuildId,
                    canonicalRequest, now);
            auditLogService.recordPlatformOperatorAction(actor, requestId,
                    "projection_rebuild.reconciled", "projection_rebuild", rebuildId,
                    "Data operator reconciled shadow projection evidence",
                    safeState(normalizedName, "CATCHING_UP"), safeState(normalizedName,
                            evidence.passed() ? "RECONCILED" : "RECONCILIATION_FAILED"));
            metrics.afterCommit("reconcile", evidence.passed() ? "passed" : "mismatch");
            return new ReconciliationView(rebuildId, evidence.attempt(), evidence.passed(),
                    evidence.sourceSha256(), evidence.mismatchFamilies(),
                    evidence.catchUpComplete(), evidence.catchUpAppliedInputCount(),
                    evidence.catchUpRemainingInputCount(), false);
        });
        if (result == null) {
            throw new IllegalStateException("Projection reconciliation returned no result");
        }
        return result;
    }

    public ActivationView activate(String rawCredential, String idempotencyKey,
            String projectionName, UUID rebuildId, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        String normalizedName = requireProjectionName(projectionName);
        ActivationView result = transaction.execute(status -> {
            PlatformOperatorContext actor = requireDataOperator(rawCredential);
            ProjectionDefinition definition = requireDefinitionForUpdate(normalizedName);
            String operation = "ACTIVATE_REBUILD";
            String canonicalRequest = "projection=" + normalizedName + "\nrebuild=" + rebuildId;
            DataOperationRequest replay = findReplay(actor, operation, idempotencyKey,
                    rebuildId, canonicalRequest);
            if (replay != null) {
                RebuildView view = requireView(normalizedName, rebuildId, true);
                return new ActivationView(rebuildId, view.generationId(),
                        "ACTIVATED".equals(view.lifecycleStatus()), List.of(), true);
            }
            ProjectionRebuildTarget target = requireTargetForUpdate(definition, rebuildId);
            if (!"RECONCILED".equals(target.lifecycleStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Projection rebuild must be reconciled before activation");
            }
            List<ProjectionReconciliationFamily> mismatches = List.of();
            String currentSource = reconciliationEngine.currentSourceSha256();
            if (!constantTimeEquals(currentSource, target.reconciliationSourceSha256())) {
                ProjectionReconciliationEngine.ReconciliationResult refreshed =
                        reconciliationEngine.catchUpAndReconcile(target, actor.operatorId());
                mismatches = refreshed.mismatchFamilies();
                if (!refreshed.passed()) {
                    persistRequest(actor, operation, idempotencyKey, rebuildId, rebuildId,
                            canonicalRequest, now());
                    metrics.afterCommit("activate", "reconciliation_mismatch");
                    return new ActivationView(rebuildId, target.projectionGenerationId(),
                            false, mismatches, false);
                }
            }
            requireFivePassingCurrentFamilies(rebuildId);
            UUID priorActive = definition.activeGenerationId();
            Instant now = now();
            int retired = jdbcTemplate.update("""
                    UPDATE projection_generations
                    SET lifecycle_status = 'RETIRED', retired_at = ?
                    WHERE id = ? AND projection_definition_id = ?
                      AND lifecycle_status = 'ACTIVE'
                    """, Timestamp.from(now), priorActive, definition.definitionId());
            int activated = jdbcTemplate.update("""
                    UPDATE projection_generations
                    SET lifecycle_status = 'ACTIVE', activated_at = ?, retired_at = NULL
                    WHERE id = ? AND projection_definition_id = ?
                      AND lifecycle_status = 'RECONCILED'
                    """, Timestamp.from(now), target.projectionGenerationId(),
                    definition.definitionId());
            int pointer = jdbcTemplate.update("""
                    UPDATE projection_definitions
                    SET active_generation_id = ?, updated_at = ?
                    WHERE id = ? AND active_generation_id = ?
                    """, target.projectionGenerationId(), Timestamp.from(now),
                    definition.definitionId(), priorActive);
            int rebuild = jdbcTemplate.update("""
                    UPDATE projection_rebuilds
                    SET lifecycle_status = 'ACTIVATED', previous_active_generation_id = ?,
                        updated_at = ?, completed_at = ?
                    WHERE id = ? AND lifecycle_status = 'RECONCILED'
                    """, priorActive, Timestamp.from(now), Timestamp.from(now), rebuildId);
            if (retired != 1 || activated != 1 || pointer != 1 || rebuild != 1) {
                throw new ProjectionRebuildFenceException();
            }
            store.appendEvent(rebuildId, ProjectionRebuildEventType.ACTIVATED,
                    target.fencingEpoch(), actor.operatorId(), null,
                    target.processedInputCount(), now);
            persistRequest(actor, operation, idempotencyKey, rebuildId, rebuildId,
                    canonicalRequest, now);
            auditLogService.recordPlatformOperatorAction(actor, requestId,
                    "projection_rebuild.activated", "projection_rebuild", rebuildId,
                    "Data operator atomically activated reconciled generation",
                    safeState(normalizedName, "RECONCILED"),
                    safeState(normalizedName, "ACTIVATED"));
            metrics.afterCommit("activate", "activated");
            return new ActivationView(rebuildId, target.projectionGenerationId(),
                    true, mismatches, false);
        });
        if (result == null) {
            throw new IllegalStateException("Projection activation returned no result");
        }
        return result;
    }

    public RollbackView rollback(String rawCredential, String idempotencyKey,
            String projectionName, UUID targetGenerationId, String requestId) {
        requireIdempotencyKey(idempotencyKey);
        String normalizedName = requireProjectionName(projectionName);
        RollbackView result = transaction.execute(status -> {
            PlatformOperatorContext actor = requireDataOperator(rawCredential);
            ProjectionDefinition definition = requireDefinitionForUpdate(normalizedName);
            String operation = "ROLLBACK_PROJECTION";
            String canonicalRequest = "projection=" + normalizedName
                    + "\ntargetGeneration=" + targetGenerationId;
            DataOperationRequest replay = findReplay(actor, operation, idempotencyKey,
                    targetGenerationId, canonicalRequest);
            if (replay != null) {
                return new RollbackView(normalizedName, targetGenerationId, true, true);
            }
            RetainedGeneration target = requireRetainedGenerationForUpdate(
                    definition, targetGenerationId);
            String currentSource = reconciliationEngine.currentSourceSha256();
            if (!constantTimeEquals(currentSource, target.reconciliationSourceSha256())) {
                if (!target.directRebuildTarget()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Retained baseline is stale and cannot be caught up safely");
                }
                ProjectionRebuildTarget rebuildTarget = requireTargetForUpdate(definition,
                        target.rebuildId());
                ProjectionReconciliationEngine.ReconciliationResult refreshed =
                        reconciliationEngine.catchUpAndReconcile(rebuildTarget,
                                actor.operatorId());
                if (!refreshed.passed()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Retained generation no longer reconciles with active truth");
                }
            }
            requireFivePassingCurrentFamilies(target.rebuildId());
            UUID priorActive = definition.activeGenerationId();
            Instant now = now();
            int retired = jdbcTemplate.update("""
                    UPDATE projection_generations
                    SET lifecycle_status = 'RETIRED', retired_at = ?
                    WHERE id = ? AND projection_definition_id = ?
                      AND lifecycle_status = 'ACTIVE'
                    """, Timestamp.from(now), priorActive, definition.definitionId());
            int restored = jdbcTemplate.update("""
                    UPDATE projection_generations
                    SET lifecycle_status = 'ACTIVE', activated_at = ?, retired_at = NULL
                    WHERE id = ? AND projection_definition_id = ?
                      AND lifecycle_status = 'RETIRED' AND handler_version = ?
                    """, Timestamp.from(now), targetGenerationId,
                    definition.definitionId(), definition.handlerVersion());
            int pointer = jdbcTemplate.update("""
                    UPDATE projection_definitions
                    SET active_generation_id = ?, updated_at = ?
                    WHERE id = ? AND active_generation_id = ?
                    """, targetGenerationId, Timestamp.from(now),
                    definition.definitionId(), priorActive);
            if (retired != 1 || restored != 1 || pointer != 1) {
                throw new ProjectionRebuildFenceException();
            }
            store.appendEvent(target.rebuildId(), ProjectionRebuildEventType.ROLLED_BACK,
                    target.fencingEpoch(), actor.operatorId(), null, 0, now);
            persistRequest(actor, operation, idempotencyKey, targetGenerationId,
                    targetGenerationId, canonicalRequest, now);
            auditLogService.recordPlatformOperatorAction(actor, requestId,
                    "projection_generation.rolled_back", "projection_generation",
                    targetGenerationId,
                    "Data operator atomically restored a retained reconciled generation",
                    safeState(normalizedName, "RETIRED"),
                    safeState(normalizedName, "ACTIVE"));
            metrics.afterCommit("rollback", "activated");
            return new RollbackView(normalizedName, targetGenerationId, true, false);
        });
        if (result == null) {
            throw new IllegalStateException("Projection rollback returned no result");
        }
        return result;
    }

    private RebuildView mutateRebuild(String rawCredential, String idempotencyKey,
            String projectionName, UUID rebuildId, String operation, String metricOperation,
            String requestId, RebuildMutation mutation) {
        requireIdempotencyKey(idempotencyKey);
        String normalizedName = requireProjectionName(projectionName);
        RebuildView result = transaction.execute(status -> {
            PlatformOperatorContext actor = requireDataOperator(rawCredential);
            ProjectionDefinition definition = requireDefinitionForUpdate(normalizedName);
            String canonicalRequest = "projection=" + normalizedName + "\nrebuild=" + rebuildId;
            DataOperationRequest replay = findReplay(actor, operation, idempotencyKey,
                    rebuildId, canonicalRequest);
            if (replay != null) {
                return requireView(normalizedName, rebuildId, true);
            }
            ProjectionRebuildTarget target = requireTargetForUpdate(definition, rebuildId);
            String resultingStatus = mutation.apply(actor, definition, target);
            Instant now = now();
            persistRequest(actor, operation, idempotencyKey, rebuildId, rebuildId,
                    canonicalRequest, now);
            auditLogService.recordPlatformOperatorAction(actor, requestId,
                    "projection_rebuild." + metricOperation + "ed", "projection_rebuild",
                    rebuildId, "Data operator changed projection rebuild lifecycle",
                    safeState(normalizedName, target.lifecycleStatus()),
                    safeState(normalizedName, resultingStatus));
            metrics.afterCommit(metricOperation, resultingStatus.toLowerCase());
            return requireView(normalizedName, rebuildId, false);
        });
        return requireResult(result);
    }

    private ProjectionDefinition requireDefinitionForUpdate(String projectionName) {
        List<ProjectionDefinition> matches = jdbcTemplate.query("""
                SELECT id, projection_name, handler_version, lifecycle_status,
                       active_generation_id
                FROM projection_definitions
                WHERE projection_name = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new ProjectionDefinition(
                        resultSet.getObject(1, UUID.class), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getString(4),
                        resultSet.getObject(5, UUID.class)), projectionName);
        if (matches.size() != 1 || !"ENABLED".equals(matches.getFirst().lifecycleStatus())
                || !ProjectionNames.HANDLER_VERSION.equals(
                        matches.getFirst().handlerVersion())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Projection definition was not found");
        }
        return matches.getFirst();
    }

    private ProjectionRebuildTarget requireTargetForUpdate(ProjectionDefinition definition,
            UUID rebuildId) {
        if (rebuildId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Projection rebuild was not found");
        }
        List<ProjectionRebuildTarget> matches = jdbcTemplate.query("""
                SELECT rebuild.id, rebuild.projection_definition_id,
                       rebuild.projection_generation_id, rebuild.cutoff_manifest_id,
                       definition.projection_name, rebuild.handler_version,
                       rebuild.lifecycle_status, rebuild.fencing_epoch,
                       rebuild.processed_input_count, rebuild.expected_input_count,
                       rebuild.current_reconciliation_attempt,
                       rebuild.reconciliation_source_sha256,
                       rebuild.catchup_cycle_count, rebuild.catchup_input_count,
                       rebuild.catchup_remaining_count,
                       rebuild.latest_catchup_source_sha256
                FROM projection_rebuilds rebuild
                JOIN projection_definitions definition
                  ON definition.id = rebuild.projection_definition_id
                WHERE rebuild.id = ? AND rebuild.projection_definition_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new ProjectionRebuildTarget(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class),
                        resultSet.getObject(4, UUID.class), resultSet.getString(5),
                        resultSet.getString(6), resultSet.getString(7),
                        resultSet.getLong(8), resultSet.getLong(9), resultSet.getLong(10),
                        resultSet.getInt(11), resultSet.getString(12),
                        resultSet.getInt(13), resultSet.getLong(14),
                        resultSet.getLong(15), resultSet.getString(16)), rebuildId,
                definition.definitionId());
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Projection rebuild was not found");
        }
        return matches.getFirst();
    }

    private RetainedGeneration requireRetainedGenerationForUpdate(
            ProjectionDefinition definition, UUID generationId) {
        List<RetainedGeneration> direct = jdbcTemplate.query("""
                SELECT generation.id, rebuild.id, rebuild.fencing_epoch,
                       rebuild.reconciliation_source_sha256, TRUE
                FROM projection_generations generation
                JOIN projection_rebuilds rebuild
                  ON rebuild.projection_generation_id = generation.id
                 AND rebuild.projection_definition_id = generation.projection_definition_id
                WHERE generation.id = ? AND generation.projection_definition_id = ?
                  AND generation.lifecycle_status = 'RETIRED'
                  AND generation.handler_version = ?
                  AND rebuild.lifecycle_status = 'ACTIVATED'
                FOR UPDATE
                """, (resultSet, rowNumber) -> new RetainedGeneration(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getLong(3),
                        resultSet.getString(4), resultSet.getBoolean(5)), generationId,
                definition.definitionId(), definition.handlerVersion());
        if (!direct.isEmpty()) {
            return direct.getFirst();
        }
        List<RetainedGeneration> baseline = jdbcTemplate.query("""
                SELECT generation.id, rebuild.id, rebuild.fencing_epoch,
                       rebuild.reconciliation_source_sha256, FALSE
                FROM projection_generations generation
                JOIN projection_rebuilds rebuild
                  ON rebuild.previous_active_generation_id = generation.id
                 AND rebuild.projection_definition_id = generation.projection_definition_id
                WHERE generation.id = ? AND generation.projection_definition_id = ?
                  AND generation.lifecycle_status = 'RETIRED'
                  AND generation.handler_version = ?
                  AND rebuild.lifecycle_status = 'ACTIVATED'
                ORDER BY rebuild.completed_at DESC
                FOR UPDATE
                """, (resultSet, rowNumber) -> new RetainedGeneration(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getLong(3),
                        resultSet.getString(4), resultSet.getBoolean(5)), generationId,
                definition.definitionId(), definition.handlerVersion());
        if (baseline.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Rollback target is not a retained reconciled generation");
        }
        return baseline.getFirst();
    }

    private void requireFivePassingCurrentFamilies(UUID rebuildId) {
        Integer passing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM projection_reconciliations reconciliation
                JOIN projection_rebuilds rebuild
                  ON rebuild.id = reconciliation.projection_rebuild_id
                 AND rebuild.current_reconciliation_attempt =
                     reconciliation.reconciliation_attempt
                WHERE rebuild.id = ? AND reconciliation.result_status = 'PASS'
                """, Integer.class, rebuildId);
        if (passing == null || passing != ProjectionReconciliationFamily.values().length) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Projection generation lacks complete reconciliation evidence");
        }
    }

    private RebuildView requireView(String projectionName, UUID rebuildId, boolean replayed) {
        List<RebuildView> matches = jdbcTemplate.query("""
                SELECT rebuild.id, rebuild.projection_generation_id,
                       generation.generation_number, rebuild.lifecycle_status,
                       rebuild.processed_input_count, rebuild.expected_input_count,
                       rebuild.fencing_epoch, rebuild.cutoff_manifest_id,
                       manifest.canonical_manifest_sha256,
                       rebuild.current_reconciliation_attempt, rebuild.created_at,
                       rebuild.updated_at, rebuild.completed_at,
                       rebuild.catchup_cycle_count, rebuild.catchup_input_count,
                       rebuild.catchup_remaining_count,
                       rebuild.latest_catchup_source_sha256
                FROM projection_rebuilds rebuild
                JOIN projection_definitions definition
                  ON definition.id = rebuild.projection_definition_id
                JOIN projection_generations generation
                  ON generation.id = rebuild.projection_generation_id
                JOIN projection_cutoff_manifests manifest
                  ON manifest.id = rebuild.cutoff_manifest_id
                WHERE rebuild.id = ? AND definition.projection_name = ?
                """, (resultSet, rowNumber) -> new RebuildView(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getLong(3),
                        resultSet.getString(4), resultSet.getLong(5), resultSet.getLong(6),
                        resultSet.getLong(7), resultSet.getObject(8, UUID.class),
                        resultSet.getString(9), resultSet.getInt(10),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 11),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 12),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 13),
                        resultSet.getInt(14), resultSet.getLong(15),
                        resultSet.getLong(16), resultSet.getString(17), replayed),
                rebuildId, projectionName);
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Projection rebuild was not found");
        }
        return matches.getFirst();
    }

    private ReconciliationView latestReconciliation(String projectionName, UUID rebuildId,
            boolean replayed) {
        RebuildView view = requireView(projectionName, rebuildId, replayed);
        List<ProjectionReconciliationFamily> mismatches = jdbcTemplate.query("""
                SELECT invariant_family FROM projection_reconciliations
                WHERE projection_rebuild_id = ? AND reconciliation_attempt = ?
                  AND result_status = 'FAIL'
                ORDER BY invariant_family
                """, (resultSet, rowNumber) -> ProjectionReconciliationFamily.valueOf(
                        resultSet.getString(1)), rebuildId,
                view.currentReconciliationAttempt());
        String source = jdbcTemplate.queryForObject("""
                SELECT reconciliation_source_sha256 FROM projection_rebuilds WHERE id = ?
                """, String.class, rebuildId);
        return new ReconciliationView(rebuildId, view.currentReconciliationAttempt(),
                mismatches.isEmpty() && view.currentReconciliationAttempt() > 0,
                source, mismatches, view.catchupRemainingCount() == 0,
                0, view.catchupRemainingCount(), replayed);
    }

    private PlatformOperatorContext requireDataOperator(String rawCredential) {
        PlatformOperatorContext actor = authenticator.authenticateContextForUpdate(rawCredential);
        if (actor.role() != PlatformOperatorRole.DATA_OPERATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Data operator role is required");
        }
        return actor;
    }

    private DataOperationRequest findReplay(PlatformOperatorContext actor, String operation,
            String idempotencyKey, UUID targetId, String canonicalRequest) {
        List<SensitiveValueHasher.VersionedDigest> identities = requestIdentities(
                actor, operation, idempotencyKey);
        MapSqlParameterSource parameters = new MapSqlParameterSource("identities",
                identities.stream().map(SensitiveValueHasher.VersionedDigest::digest).toList());
        List<DataOperationRequest> matches = namedJdbcTemplate.query("""
                SELECT platform_operator_id, platform_credential_id, operation,
                       request_digest, request_digest_key_version, target_id,
                       result_id, result_status
                FROM platform_data_operation_requests
                WHERE request_identity_hmac IN (:identities)
                FOR UPDATE
                """, parameters, (resultSet, rowNumber) -> new DataOperationRequest(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getString(3),
                        resultSet.getString(4), resultSet.getString(5),
                        resultSet.getObject(6, UUID.class),
                        resultSet.getObject(7, UUID.class), resultSet.getString(8)));
        if (matches.isEmpty()) {
            return null;
        }
        DataOperationRequest request = matches.getFirst();
        String expectedDigest = hasher.hmacSha256(REQUEST_DIGEST_DOMAIN,
                canonicalRequest, request.requestDigestKeyVersion());
        if (matches.size() != 1
                || !request.platformOperatorId().equals(actor.operatorId())
                || !request.platformCredentialId().equals(actor.credentialId())
                || !operation.equals(request.operation())
                || !targetId.equals(request.targetId())
                || !constantTimeEquals(request.requestDigest(), expectedDigest)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different data operation");
        }
        return request;
    }

    private void persistRequest(PlatformOperatorContext actor, String operation,
            String idempotencyKey, UUID targetId, UUID resultId,
            String canonicalRequest, Instant now) {
        SensitiveValueHasher.VersionedDigest identity = requestIdentities(
                actor, operation, idempotencyKey).getFirst();
        String digest = hasher.hmacSha256(REQUEST_DIGEST_DOMAIN,
                canonicalRequest, identity.keyVersion());
        jdbcTemplate.update("""
                INSERT INTO platform_data_operation_requests (
                    id, platform_operator_id, platform_credential_id, operation,
                    request_identity_hmac, request_identity_key_version,
                    request_digest, request_digest_key_version, target_id,
                    result_id, result_status, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?)
                """, UUID.randomUUID(), actor.operatorId(), actor.credentialId(), operation,
                identity.digest(), identity.keyVersion(), digest, identity.keyVersion(),
                targetId, resultId, Timestamp.from(now), Timestamp.from(now));
    }

    private List<SensitiveValueHasher.VersionedDigest> requestIdentities(
            PlatformOperatorContext actor, String operation, String idempotencyKey) {
        return hasher.hmacSha256Keyring(REQUEST_IDENTITY_DOMAIN,
                actor.operatorId() + "\n" + actor.credentialId() + "\n"
                        + operation + "\n" + idempotencyKey);
    }

    private String requireProjectionName(String value) {
        if (ProjectionNames.PAYMENT_RISK.equals(value)
                || ProjectionNames.MERCHANT_RISK.equals(value)) {
            return value;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Projection definition was not found");
    }

    private void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 255 characters");
        }
    }

    private Map<String, Object> safeState(String projectionName, String lifecycleStatus) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("projectionName", projectionName);
        state.put("lifecycleStatus", lifecycleStatus);
        return state;
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private RebuildView requireResult(RebuildView result) {
        if (result == null) {
            throw new IllegalStateException("Projection rebuild returned no result");
        }
        return result;
    }

    @FunctionalInterface
    private interface RebuildMutation {
        String apply(PlatformOperatorContext actor, ProjectionDefinition definition,
                ProjectionRebuildTarget target);
    }

    private record ProjectionDefinition(UUID definitionId, String projectionName,
            String handlerVersion, String lifecycleStatus, UUID activeGenerationId) {
    }

    private record DataOperationRequest(UUID platformOperatorId,
            UUID platformCredentialId, String operation, String requestDigest,
            String requestDigestKeyVersion, UUID targetId, UUID resultId,
            String resultStatus) {
    }

    private record RetainedGeneration(UUID generationId, UUID rebuildId,
            long fencingEpoch, String reconciliationSourceSha256,
            boolean directRebuildTarget) {
    }

    public record RebuildView(UUID rebuildId, UUID generationId, long generationNumber,
            String lifecycleStatus, long processedInputCount, long expectedInputCount,
            long fencingEpoch, UUID cutoffManifestId, String cutoffManifestSha256,
            int currentReconciliationAttempt, Instant createdAt, Instant updatedAt,
            Instant completedAt, int catchupCycleCount, long catchupInputCount,
            long catchupRemainingCount, String latestCatchupSourceSha256,
            boolean replayed) {
    }

    public record ReconciliationView(UUID rebuildId, int attempt, boolean passed,
            String sourceSha256, List<ProjectionReconciliationFamily> mismatchFamilies,
            boolean catchUpComplete, int catchUpAppliedInputCount,
            long catchUpRemainingInputCount, boolean replayed) {
    }

    public record ActivationView(UUID rebuildId, UUID generationId, boolean activated,
            List<ProjectionReconciliationFamily> mismatchFamilies, boolean replayed) {
    }

    public record RollbackView(String projectionName, UUID generationId,
            boolean activated, boolean replayed) {
    }
}
