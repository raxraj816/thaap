package com.thaap.payments.fraudlist;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FraudListManagementService {

    private static final int MAX_ENTRIES_PER_VERSION = 1_000;
    private static final int MAX_LISTS_PER_MERCHANT = 64;

    private final JdbcTemplate jdbcTemplate;
    private final FraudListIdentityService identityService;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final FraudListMetrics metrics;

    public FraudListManagementService(JdbcTemplate jdbcTemplate, FraudListIdentityService identityService,
            AuditLogService auditLogService, Clock clock, FraudListMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.identityService = identityService;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public List<ListView> list(RiskOperatorContext context) {
        return jdbcTemplate.query("""
                SELECT list.id, list.list_code, list.display_name, list.identity_type,
                       list.generic_namespace, version.id, version.version_number,
                       version.effective_from,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM fraud_list_lifecycle_events event
                            WHERE event.merchant_id = list.merchant_id
                              AND event.fraud_list_version_id = version.id
                              AND event.event_type = 'VERSION_DEACTIVATED'
                       ) THEN FALSE ELSE TRUE END AS active,
                       list.created_at
                  FROM fraud_lists list
                  JOIN fraud_list_versions version
                    ON version.merchant_id = list.merchant_id
                   AND version.fraud_list_id = list.id
                 WHERE list.merchant_id = ?
                 ORDER BY list.list_code, version.version_number
                """, (resultSet, rowNumber) -> listView(resultSet), context.merchantId());
    }

    @Transactional
    public ListView create(RiskOperatorContext context, String code, String displayName,
            FraudListIdentityType identityType, FraudListGenericNamespace namespace, String requestId) {
        requireManagerForUpdate(context);
        validateNamespace(identityType, namespace);
        Integer listCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fraud_lists WHERE merchant_id = ?", Integer.class, context.merchantId());
        if (listCount != null && listCount >= MAX_LISTS_PER_MERCHANT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Merchant has reached its 64 fraud-list limit");
        }
        Instant now = clock.instant();
        UUID listId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO fraud_lists
                        (id, merchant_id, list_code, display_name, identity_type, generic_namespace,
                         created_by_operator_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, listId, context.merchantId(), code, displayName, identityType.name(),
                    namespace == null ? null : namespace.name(), context.operatorId(), Timestamp.from(now));
            jdbcTemplate.update("""
                    INSERT INTO fraud_list_versions
                        (id, merchant_id, fraud_list_id, version_number, effective_from,
                         created_by_operator_id, created_at)
                    VALUES (?, ?, ?, 1, ?, ?, ?)
                    """, versionId, context.merchantId(), listId, Timestamp.from(now),
                    context.operatorId(), Timestamp.from(now));
            insertLifecycle(context, listId, versionId, null, versionId,
                    "VERSION_ACTIVATED", now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Fraud-list code already exists for merchant", exception);
        }
        auditLogService.recordRiskOperatorAction(context, requestId, "fraud_list.created", "FraudList", listId,
                "MANUAL_RISK_CONTROL", Map.of(), Map.of("listCode", code, "identityType", identityType.name(),
                        "version", 1));
        metrics.recordManagement("LIST_CREATED");
        return new ListView(listId, code, displayName, identityType, namespace, versionId, 1, now, true, now);
    }

    @Transactional
    public ListView createNextVersion(RiskOperatorContext context, UUID listId, String requestId) {
        requireManagerForUpdate(context);
        ListRow list = requireList(context, listId);
        VersionRow current = requireActiveVersion(context.merchantId(), listId);
        Instant now = clock.instant();
        insertLifecycle(context, listId, current.id(), null, current.id(), "VERSION_DEACTIVATED", now);
        UUID nextId = UUID.randomUUID();
        int nextNumber = current.versionNumber() + 1;
        jdbcTemplate.update("""
                INSERT INTO fraud_list_versions
                    (id, merchant_id, fraud_list_id, version_number, effective_from,
                     created_by_operator_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, nextId, context.merchantId(), listId, nextNumber, Timestamp.from(now),
                context.operatorId(), Timestamp.from(now));
        insertLifecycle(context, listId, nextId, null, nextId, "VERSION_ACTIVATED", now);
        auditLogService.recordRiskOperatorAction(context, requestId, "fraud_list.version.created", "FraudList",
                listId, "MANUAL_RISK_CONTROL", Map.of("version", current.versionNumber()),
                Map.of("version", nextNumber));
        metrics.recordManagement("VERSION_CREATED");
        return new ListView(listId, list.code(), list.displayName(), list.identityType(), list.namespace(),
                nextId, nextNumber, now, true, list.createdAt());
    }

    @Transactional
    public EntryView addEntry(RiskOperatorContext context, UUID listId, String rawValue,
            FraudDecisionAction action, FraudListReasonCode reasonCode, Instant effectiveFrom,
            Instant effectiveUntil, String requestId) {
        requireManagerForUpdate(context);
        if (action == FraudDecisionAction.ALLOW) {
            throw badRequest("Fraud-list entries cannot use ALLOW");
        }
        ListRow list = requireList(context, listId);
        VersionRow version = requireActiveVersion(context.merchantId(), listId);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fraud_list_entries
                 WHERE merchant_id = ? AND fraud_list_version_id = ?
                """, Integer.class, context.merchantId(), version.id());
        if (count != null && count >= MAX_ENTRIES_PER_VERSION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fraud-list version has reached its 1000-entry limit");
        }
        Instant now = clock.instant();
        Instant starts = effectiveFrom == null ? now : effectiveFrom;
        validateInterval(now, starts, effectiveUntil);
        FraudListIdentityService.EntryIdentity identity = identityService.identityForEntry(
                context.merchantId(), list.identityType(), list.namespace(), rawValue);
        UUID entryId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO fraud_list_entries
                        (id, merchant_id, fraud_list_id, fraud_list_version_id, identity_type,
                         canonical_value, value_hmac, hmac_key_version, action, reason_code,
                         effective_from, effective_until, created_by_operator_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, entryId, context.merchantId(), listId, version.id(), list.identityType().name(),
                    identity.canonicalValue(), identity.valueHmac(), identity.hmacKeyVersion(), action.name(),
                    reasonCode.name(), Timestamp.from(starts),
                    effectiveUntil == null ? null : Timestamp.from(effectiveUntil),
                    context.operatorId(), Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Equivalent fraud-list entry already exists in this version", exception);
        }
        auditLogService.recordRiskOperatorAction(context, requestId, "fraud_list.entry.created",
                "FraudListEntry", entryId, reasonCode.name(), Map.of(),
                Map.of("listId", listId.toString(), "version", version.versionNumber(),
                        "identityType", list.identityType().name(), "action", action.name()));
        metrics.recordManagement("ENTRY_CREATED");
        return new EntryView(entryId, listId, version.id(), version.versionNumber(), list.identityType(),
                action, reasonCode, starts, effectiveUntil, true, now);
    }

    @Transactional
    public EntryView deactivateEntry(RiskOperatorContext context, UUID listId, UUID entryId, String requestId) {
        requireManagerForUpdate(context);
        EntryView entry = jdbcTemplate.query("""
                SELECT entry.id, entry.fraud_list_id, entry.fraud_list_version_id,
                       version.version_number, entry.identity_type, entry.action, entry.reason_code,
                       entry.effective_from, entry.effective_until, entry.created_at
                  FROM fraud_list_entries entry
                  JOIN fraud_list_versions version
                    ON version.merchant_id = entry.merchant_id
                   AND version.id = entry.fraud_list_version_id
                 WHERE entry.merchant_id = ? AND entry.fraud_list_id = ? AND entry.id = ?
                 FOR UPDATE
                """, resultSet -> resultSet.next() ? entryView(resultSet, true) : null,
                context.merchantId(), listId, entryId);
        if (entry == null) {
            throw notFound("Fraud-list entry was not found");
        }
        Instant now = clock.instant();
        try {
            insertLifecycle(context, listId, entry.versionId(), entryId, entryId,
                    "ENTRY_DEACTIVATED", now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Fraud-list entry is already deactivated", exception);
        }
        auditLogService.recordRiskOperatorAction(context, requestId, "fraud_list.entry.deactivated",
                "FraudListEntry", entryId, "MANUAL_RISK_CONTROL", Map.of("active", true),
                Map.of("active", false));
        metrics.recordManagement("ENTRY_DEACTIVATED");
        return new EntryView(entry.id(), entry.listId(), entry.versionId(), entry.versionNumber(),
                entry.identityType(), entry.action(), entry.reasonCode(), entry.effectiveFrom(),
                entry.effectiveUntil(), false, entry.createdAt());
    }

    @Transactional
    public ListView deactivateVersion(RiskOperatorContext context, UUID listId, String requestId) {
        requireManagerForUpdate(context);
        ListRow list = requireList(context, listId);
        VersionRow version = requireActiveVersion(context.merchantId(), listId);
        Instant now = clock.instant();
        try {
            insertLifecycle(context, listId, version.id(), null, version.id(),
                    "VERSION_DEACTIVATED", now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Fraud-list version is already deactivated", exception);
        }
        auditLogService.recordRiskOperatorAction(context, requestId, "fraud_list.version.deactivated",
                "FraudList", listId, "MANUAL_RISK_CONTROL", Map.of("active", true), Map.of("active", false));
        metrics.recordManagement("VERSION_DEACTIVATED");
        return new ListView(listId, list.code(), list.displayName(), list.identityType(), list.namespace(),
                version.id(), version.versionNumber(), version.effectiveFrom(), false, list.createdAt());
    }

    private void requireManagerForUpdate(RiskOperatorContext context) {
        UUID merchantLock = jdbcTemplate.query("""
                SELECT id FROM merchants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null,
                context.merchantId());
        if (merchantLock == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Active merchant is required for fraud-list mutation");
        }
        String role = jdbcTemplate.query("""
                SELECT role FROM risk_operators
                 WHERE merchant_id = ? AND id = ? AND status = 'ACTIVE'
                 FOR UPDATE
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                context.merchantId(), context.operatorId());
        if (!RiskOperatorRole.RISK_MANAGER.name().equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "RISK_MANAGER role is required for fraud-list mutation");
        }
    }

    private ListRow requireList(RiskOperatorContext context, UUID listId) {
        ListRow row = jdbcTemplate.query("""
                SELECT id, list_code, display_name, identity_type, generic_namespace, created_at
                  FROM fraud_lists WHERE merchant_id = ? AND id = ? FOR UPDATE
                """, resultSet -> resultSet.next() ? listRow(resultSet) : null, context.merchantId(), listId);
        if (row == null) {
            throw notFound("Fraud list was not found");
        }
        return row;
    }

    private VersionRow requireActiveVersion(UUID merchantId, UUID listId) {
        List<VersionRow> versions = jdbcTemplate.query("""
                SELECT version.id, version.version_number, version.effective_from
                  FROM fraud_list_versions version
                 WHERE version.merchant_id = ? AND version.fraud_list_id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM fraud_list_lifecycle_events event
                        WHERE event.merchant_id = version.merchant_id
                          AND event.fraud_list_version_id = version.id
                          AND event.event_type = 'VERSION_DEACTIVATED')
                 ORDER BY version.version_number DESC FOR UPDATE OF version
                """, (resultSet, rowNumber) -> new VersionRow(resultSet.getObject(1, UUID.class),
                        resultSet.getInt(2), resultSet.getTimestamp(3).toInstant()), merchantId, listId);
        if (versions.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fraud list must have exactly one active version");
        }
        return versions.getFirst();
    }

    private void insertLifecycle(RiskOperatorContext context, UUID listId, UUID versionId, UUID entryId,
            UUID targetId, String eventType, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO fraud_list_lifecycle_events
                    (id, merchant_id, fraud_list_id, fraud_list_version_id, fraud_list_entry_id,
                     lifecycle_target_id, event_type, actor_operator_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), context.merchantId(), listId, versionId, entryId, targetId,
                eventType, context.operatorId(), Timestamp.from(now), Timestamp.from(now));
    }

    private void validateNamespace(FraudListIdentityType type, FraudListGenericNamespace namespace) {
        if ((type == FraudListIdentityType.GENERIC_STRING) != (namespace != null)) {
            throw badRequest("genericNamespace is required only for GENERIC_STRING fraud lists");
        }
    }

    private void validateInterval(Instant now, Instant effectiveFrom, Instant effectiveUntil) {
        if (effectiveFrom.isBefore(now.minusSeconds(5))) {
            throw badRequest("Fraud-list entries cannot be backdated");
        }
        if (effectiveFrom.isAfter(now.plus(Duration.ofDays(30)))) {
            throw badRequest("Fraud-list entries cannot start more than 30 days in the future");
        }
        if (effectiveUntil != null && (!effectiveUntil.isAfter(effectiveFrom)
                || effectiveUntil.isAfter(effectiveFrom.plus(Duration.ofDays(366))))) {
            throw badRequest("Fraud-list entry expiry must be after activation and within 366 days");
        }
    }

    private ListView listView(ResultSet rs) throws SQLException {
        return new ListView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                FraudListIdentityType.valueOf(rs.getString(4)), nullableNamespace(rs.getString(5)),
                rs.getObject(6, UUID.class), rs.getInt(7), rs.getTimestamp(8).toInstant(), rs.getBoolean(9),
                rs.getTimestamp(10).toInstant());
    }

    private ListRow listRow(ResultSet rs) throws SQLException {
        return new ListRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                FraudListIdentityType.valueOf(rs.getString(4)), nullableNamespace(rs.getString(5)),
                rs.getTimestamp(6).toInstant());
    }

    private EntryView entryView(ResultSet rs, boolean active) throws SQLException {
        Timestamp until = rs.getTimestamp(9);
        return new EntryView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getInt(4), FraudListIdentityType.valueOf(rs.getString(5)),
                FraudDecisionAction.valueOf(rs.getString(6)), FraudListReasonCode.valueOf(rs.getString(7)),
                rs.getTimestamp(8).toInstant(), until == null ? null : until.toInstant(), active,
                rs.getTimestamp(10).toInstant());
    }

    private FraudListGenericNamespace nullableNamespace(String value) {
        return value == null ? null : FraudListGenericNamespace.valueOf(value);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message, RuntimeException cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
    }

    private record ListRow(UUID id, String code, String displayName, FraudListIdentityType identityType,
            FraudListGenericNamespace namespace, Instant createdAt) { }
    private record VersionRow(UUID id, int versionNumber, Instant effectiveFrom) { }

    public record ListView(UUID id, String code, String displayName, FraudListIdentityType identityType,
            FraudListGenericNamespace genericNamespace, UUID versionId, int versionNumber,
            Instant effectiveFrom, boolean active, Instant createdAt) { }
    public record EntryView(UUID id, UUID listId, UUID versionId, int versionNumber,
            FraudListIdentityType identityType, FraudDecisionAction action, FraudListReasonCode reasonCode,
            Instant effectiveFrom, Instant effectiveUntil, boolean active, Instant createdAt) { }
}
