package com.thaap.payments.investigation;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.workspace.SavedSearchLifecycleStatus;
import com.thaap.payments.workspace.SavedSearchVisibility;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentInvestigationSavedViewService {

    private final PaymentInvestigationSavedViewRepository repository;
    private final RiskOperatorRepository operatorRepository;
    private final PaymentInvestigationFilterCodec filterCodec;
    private final PaymentInvestigationSearchService searchService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public PaymentInvestigationSavedViewService(
            PaymentInvestigationSavedViewRepository repository,
            RiskOperatorRepository operatorRepository,
            PaymentInvestigationFilterCodec filterCodec,
            PaymentInvestigationSearchService searchService,
            AuditLogService auditLogService, Clock clock) {
        this.repository = repository;
        this.operatorRepository = operatorRepository;
        this.filterCodec = filterCodec;
        this.searchService = searchService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public SavedView create(RiskOperatorContext context, String name,
            SavedSearchVisibility visibility, PaymentInvestigationFilter filter,
            PaymentInvestigationSort sort, Set<PaymentInvestigationColumn> columns,
            String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        authorizeVisibility(actor, visibility);
        Instant now = now();
        Definition definition = definition(name, filter, sort, columns, now);
        PaymentInvestigationSavedView view = new PaymentInvestigationSavedView(
                context.merchantId(), context.operatorId(), definition.name(), visibility,
                PaymentInvestigationFilterCodec.SCHEMA_VERSION, definition.canonicalJson(),
                definition.sort(), definition.columnsJson(), definition.digest(), now);
        save(view);
        auditLogService.recordRiskOperatorAction(context, requestId,
                "payment_investigation_saved_view.created", "payment_investigation_saved_view",
                view.getId(), "Risk operator created a payment investigation saved view",
                null, safeState(view));
        return savedView(view, definition.filter(), definition.columns());
    }

    @Transactional(readOnly = true)
    public List<SavedView> list(RiskOperatorContext context) {
        requireLiveActor(context);
        return repository.findVisible(context.merchantId(), context.operatorId()).stream()
                .map(this::validatedView).toList();
    }

    @Transactional
    public SavedView update(RiskOperatorContext context, UUID id, long expectedVersion,
            String name, SavedSearchVisibility visibility, PaymentInvestigationFilter filter,
            PaymentInvestigationSort sort, Set<PaymentInvestigationColumn> columns,
            String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        PaymentInvestigationSavedView view = locked(id, context.merchantId());
        authorizeManage(actor, view);
        authorizeVisibility(actor, visibility);
        Definition definition = definition(name, filter, sort, columns, now());
        Map<String, Object> before = safeState(view);
        view.update(expectedVersion, definition.name(), visibility,
                PaymentInvestigationFilterCodec.SCHEMA_VERSION, definition.canonicalJson(),
                definition.sort(), definition.columnsJson(), definition.digest(), now());
        save(view);
        auditLogService.recordRiskOperatorAction(context, requestId,
                "payment_investigation_saved_view.updated", "payment_investigation_saved_view",
                view.getId(), "Risk operator updated a payment investigation saved view",
                before, safeState(view));
        return savedView(view, definition.filter(), definition.columns());
    }

    @Transactional
    public SavedView disable(RiskOperatorContext context, UUID id, long expectedVersion,
            String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        PaymentInvestigationSavedView view = locked(id, context.merchantId());
        authorizeManage(actor, view);
        PaymentInvestigationFilter filter = validatedFilter(view);
        Set<PaymentInvestigationColumn> columns = filterCodec.decodeColumns(
                view.getVisibleColumnsJson());
        Map<String, Object> before = safeState(view);
        view.disable(expectedVersion, now());
        repository.saveAndFlush(view);
        auditLogService.recordRiskOperatorAction(context, requestId,
                "payment_investigation_saved_view.disabled", "payment_investigation_saved_view",
                view.getId(), "Risk operator disabled a payment investigation saved view",
                before, safeState(view));
        return savedView(view, filter, columns);
    }

    @Transactional
    public PaymentInvestigationSearchService.SearchPage execute(RiskOperatorContext context,
            UUID id, Integer limit, String cursor) {
        liveActorForUpdate(context);
        PaymentInvestigationSavedView view = locked(id, context.merchantId());
        if (view.getLifecycleStatus() != SavedSearchLifecycleStatus.ACTIVE
                || (!view.getOwnerOperatorId().equals(context.operatorId())
                    && view.getVisibility() != SavedSearchVisibility.MERCHANT_SHARED)) {
            throw notFound();
        }
        PaymentInvestigationFilter filter = validatedFilter(view);
        filterCodec.decodeColumns(view.getVisibleColumnsJson());
        return searchService.search(context, filter, view.getSortCode(), limit, cursor);
    }

    private SavedView validatedView(PaymentInvestigationSavedView view) {
        PaymentInvestigationFilter filter = validatedFilter(view);
        Set<PaymentInvestigationColumn> columns = filterCodec.decodeColumns(
                view.getVisibleColumnsJson());
        return savedView(view, filter, columns);
    }

    private PaymentInvestigationFilter validatedFilter(PaymentInvestigationSavedView view) {
        PaymentInvestigationFilter filter = filterCodec.decodeStored(view.getFilterSchemaVersion(),
                view.getCanonicalFilterJson(), now());
        String digest = filterCodec.digest(view.getCanonicalFilterJson(), view.getSortCode(),
                view.getVisibleColumnsJson());
        if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8),
                view.getDefinitionDigest().getBytes(StandardCharsets.UTF_8))) {
            throw new PaymentSavedViewUpgradeRequiredException();
        }
        return filter;
    }

    private Definition definition(String rawName, PaymentInvestigationFilter filter,
            PaymentInvestigationSort rawSort, Set<PaymentInvestigationColumn> columns,
            Instant now) {
        String name = normalizeName(rawName);
        PaymentInvestigationSort sort = rawSort == null
                ? PaymentInvestigationSort.CREATED_AT_DESC : rawSort;
        PaymentInvestigationFilterCodec.NormalizedFilter normalized = filterCodec
                .normalize(filter, now);
        String columnsJson = filterCodec.columnsJson(columns);
        Set<PaymentInvestigationColumn> normalizedColumns = filterCodec.decodeColumns(columnsJson);
        String digest = filterCodec.digest(normalized.canonicalJson(), sort, columnsJson);
        return new Definition(name, normalized.filter(), normalized.canonicalJson(), sort,
                normalizedColumns, columnsJson, digest);
    }

    private void save(PaymentInvestigationSavedView view) {
        try {
            repository.saveAndFlush(view);
        } catch (DataIntegrityViolationException exception) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAVED_VIEW_NAME_CONFLICT);
        }
    }

    private String normalizeName(String rawName) {
        if (rawName == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAVED_VIEW_NAME_INVALID);
        }
        String value = Normalizer.normalize(rawName, Normalizer.Form.NFKC).strip();
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 80 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAVED_VIEW_NAME_INVALID);
        }
        return value;
    }

    private void authorizeVisibility(RiskOperator actor, SavedSearchVisibility visibility) {
        if (visibility == null) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAVED_VIEW_VISIBILITY_INVALID);
        }
        if (visibility == SavedSearchVisibility.MERCHANT_SHARED
                && actor.getRole() != RiskOperatorRole.RISK_MANAGER) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAVED_VIEW_MANAGER_REQUIRED);
        }
    }

    private void authorizeManage(RiskOperator actor, PaymentInvestigationSavedView view) {
        boolean owner = view.getOwnerOperatorId().equals(actor.getId());
        boolean sharedManager = actor.getRole() == RiskOperatorRole.RISK_MANAGER
                && view.getVisibility() == SavedSearchVisibility.MERCHANT_SHARED;
        if (!owner && !sharedManager) throw notFound();
    }

    private RiskOperator liveActorForUpdate(RiskOperatorContext context) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantIdForUpdate(
                context.operatorId(), context.merchantId()).filter(RiskOperator::isActive)
                .orElseThrow(this::notFound);
        if (actor.getRole() != context.role()) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_OPERATOR_STALE);
        }
        return actor;
    }

    private void requireLiveActor(RiskOperatorContext context) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantId(
                context.operatorId(), context.merchantId()).filter(RiskOperator::isActive)
                .orElseThrow(this::notFound);
        if (actor.getRole() != context.role()) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_OPERATOR_STALE);
        }
    }

    private PaymentInvestigationSavedView locked(UUID id, UUID merchantId) {
        return repository.findByIdAndMerchantIdForUpdate(id, merchantId)
                .orElseThrow(this::notFound);
    }

    private SavedView savedView(PaymentInvestigationSavedView view,
            PaymentInvestigationFilter filter, Set<PaymentInvestigationColumn> columns) {
        return new SavedView(view.getId(), view.getOwnerOperatorId(), view.getName(),
                view.getVisibility(), view.getFilterSchemaVersion(), filter,
                view.getSortCode(), columns.stream().sorted().toList(),
                view.getDefinitionDigest(), view.getLifecycleStatus(),
                view.getLifecycleVersion(), view.getCreatedAt(), view.getUpdatedAt(),
                view.getDisabledAt());
    }

    private Map<String, Object> safeState(PaymentInvestigationSavedView view) {
        return Map.of("visibility", view.getVisibility().name(),
                "schemaVersion", view.getFilterSchemaVersion(),
                "sort", view.getSortCode().name(),
                "lifecycle", view.getLifecycleStatus().name(),
                "lifecycleVersion", view.getLifecycleVersion(),
                "definitionDigest", view.getDefinitionDigest());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private ResponseStatusException notFound() {
        return new PublicApiException(PublicApiErrorDescriptor.SAVED_VIEW_NOT_FOUND);
    }

    public record SavedView(UUID id, UUID ownerOperatorId, String name,
            SavedSearchVisibility visibility, String filterSchemaVersion,
            PaymentInvestigationFilter filter, PaymentInvestigationSort sort,
            List<PaymentInvestigationColumn> visibleColumns, String definitionDigest,
            SavedSearchLifecycleStatus lifecycleStatus, long lifecycleVersion,
            Instant createdAt, Instant updatedAt, Instant disabledAt) {
    }

    private record Definition(String name, PaymentInvestigationFilter filter,
            String canonicalJson, PaymentInvestigationSort sort,
            Set<PaymentInvestigationColumn> columns, String columnsJson, String digest) {
    }
}
