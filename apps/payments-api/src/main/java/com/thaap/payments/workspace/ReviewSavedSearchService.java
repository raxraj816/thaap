package com.thaap.payments.workspace;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewSavedSearchService {

    private final ReviewSavedSearchRepository repository;
    private final RiskOperatorRepository operatorRepository;
    private final ReviewQueueFilterCodec filterCodec;
    private final AnalystQueueService queueService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public ReviewSavedSearchService(ReviewSavedSearchRepository repository,
            RiskOperatorRepository operatorRepository, ReviewQueueFilterCodec filterCodec,
            AnalystQueueService queueService, AuditLogService auditLogService, Clock clock) {
        this.repository = repository;
        this.operatorRepository = operatorRepository;
        this.filterCodec = filterCodec;
        this.queueService = queueService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public SavedSearchView create(RiskOperatorContext context, String name,
            SavedSearchVisibility visibility, ReviewQueueFilter filter,
            ReviewQueueSort sort, Set<ReviewQueueColumn> columns, String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        authorizeVisibility(actor, visibility);
        Instant now = now();
        SearchDefinition definition = definition(name, visibility, filter, sort, columns, now);
        ReviewSavedSearch search = new ReviewSavedSearch(context.merchantId(), context.operatorId(),
                definition.name(), visibility, ReviewQueueFilterCodec.SCHEMA_VERSION,
                definition.canonicalFilterJson(), definition.sort(), definition.columnsJson(),
                definition.digest(), now);
        try {
            repository.saveAndFlush(search);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Saved search name is already in use by this owner");
        }
        auditLogService.recordRiskOperatorAction(context, requestId, "review_saved_search.created",
                "review_saved_search", search.getId(), "Risk operator created saved search",
                null, safeState(search));
        return view(search, definition.filter(), definition.columns());
    }

    @Transactional(readOnly = true)
    public List<SavedSearchView> list(RiskOperatorContext context) {
        requireLiveActor(context);
        Instant now = now();
        return repository.findVisible(context.merchantId(), context.operatorId()).stream()
                .map(search -> validatedView(search, now)).toList();
    }

    @Transactional
    public SavedSearchView update(RiskOperatorContext context, UUID searchId,
            long expectedVersion, String name, SavedSearchVisibility visibility,
            ReviewQueueFilter filter, ReviewQueueSort sort, Set<ReviewQueueColumn> columns,
            String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        ReviewSavedSearch search = locked(searchId, context.merchantId());
        authorizeManage(actor, search);
        authorizeVisibility(actor, visibility);
        Instant now = now();
        SearchDefinition definition = definition(name, visibility, filter, sort, columns, now);
        Map<String, Object> before = safeState(search);
        search.update(expectedVersion, definition.name(), visibility,
                ReviewQueueFilterCodec.SCHEMA_VERSION, definition.canonicalFilterJson(),
                definition.sort(), definition.columnsJson(), definition.digest(), now);
        try {
            repository.saveAndFlush(search);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Saved search name is already in use by this owner");
        }
        auditLogService.recordRiskOperatorAction(context, requestId, "review_saved_search.updated",
                "review_saved_search", search.getId(), "Risk operator updated saved search",
                before, safeState(search));
        return view(search, definition.filter(), definition.columns());
    }

    @Transactional
    public SavedSearchView disable(RiskOperatorContext context, UUID searchId,
            long expectedVersion, String requestId) {
        RiskOperator actor = liveActorForUpdate(context);
        ReviewSavedSearch search = locked(searchId, context.merchantId());
        authorizeManage(actor, search);
        ReviewQueueFilter filter = filterCodec.decodeStored(search.getFilterSchemaVersion(),
                search.getCanonicalFilterJson(), now());
        Set<ReviewQueueColumn> columns = filterCodec.decodeVisibleColumns(search.getVisibleColumnsJson());
        Map<String, Object> before = safeState(search);
        search.disable(expectedVersion, now());
        repository.saveAndFlush(search);
        auditLogService.recordRiskOperatorAction(context, requestId, "review_saved_search.disabled",
                "review_saved_search", search.getId(), "Risk operator disabled saved search",
                before, safeState(search));
        return view(search, filter, columns);
    }

    @Transactional
    public AnalystQueueService.ReviewQueuePage execute(RiskOperatorContext context, UUID searchId,
            Integer limit, String cursor) {
        liveActorForUpdate(context);
        ReviewSavedSearch search = locked(searchId, context.merchantId());
        if (search.getLifecycleStatus() != SavedSearchLifecycleStatus.ACTIVE
                || (!search.getOwnerOperatorId().equals(context.operatorId())
                    && search.getVisibility() != SavedSearchVisibility.MERCHANT_SHARED)) {
            throw notFound();
        }
        Instant now = now();
        ReviewQueueFilter filter = filterCodec.decodeStored(search.getFilterSchemaVersion(),
                search.getCanonicalFilterJson(), now);
        Set<ReviewQueueColumn> columns = filterCodec.decodeVisibleColumns(search.getVisibleColumnsJson());
        String digest = filterCodec.digest(search.getCanonicalFilterJson(),
                search.getSortCode(), search.getVisibleColumnsJson());
        if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8),
                search.getDefinitionDigest().getBytes(StandardCharsets.UTF_8))) {
            throw new SavedSearchUpgradeRequiredException();
        }
        if (columns.isEmpty()) {
            throw new SavedSearchUpgradeRequiredException();
        }
        return queueService.search(context, filter, search.getSortCode(), limit, cursor);
    }

    private SavedSearchView validatedView(ReviewSavedSearch search, Instant now) {
        ReviewQueueFilter filter = filterCodec.decodeStored(search.getFilterSchemaVersion(),
                search.getCanonicalFilterJson(), now);
        Set<ReviewQueueColumn> columns = filterCodec.decodeVisibleColumns(search.getVisibleColumnsJson());
        String digest = filterCodec.digest(search.getCanonicalFilterJson(),
                search.getSortCode(), search.getVisibleColumnsJson());
        if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8),
                search.getDefinitionDigest().getBytes(StandardCharsets.UTF_8))) {
            throw new SavedSearchUpgradeRequiredException();
        }
        return view(search, filter, columns);
    }

    private SearchDefinition definition(String rawName, SavedSearchVisibility visibility,
            ReviewQueueFilter filter, ReviewQueueSort rawSort, Set<ReviewQueueColumn> columns,
            Instant now) {
        String name = normalizeName(rawName);
        ReviewQueueSort sort = rawSort == null ? ReviewQueueSort.CREATED_AT_DESC : rawSort;
        ReviewQueueFilterCodec.NormalizedFilter normalized = filterCodec.normalize(filter, now);
        String columnsJson = filterCodec.visibleColumnsJson(columns);
        Set<ReviewQueueColumn> normalizedColumns = filterCodec.decodeVisibleColumns(columnsJson);
        String digest = filterCodec.digest(normalized.canonicalJson(), sort, columnsJson);
        return new SearchDefinition(name, visibility, normalized.filter(),
                normalized.canonicalJson(), sort, normalizedColumns, columnsJson, digest);
    }

    private String normalizeName(String rawName) {
        if (rawName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Saved search name is required");
        }
        String name = Normalizer.normalize(rawName, Normalizer.Form.NFKC).strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 80 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Saved search name must contain between 1 and 80 safe characters");
        }
        return name;
    }

    private void authorizeVisibility(RiskOperator actor, SavedSearchVisibility visibility) {
        if (visibility == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Saved search visibility is required");
        }
        if (visibility == SavedSearchVisibility.MERCHANT_SHARED
                && actor.getRole() != RiskOperatorRole.RISK_MANAGER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a risk manager can publish a merchant-shared saved search");
        }
    }

    private void authorizeManage(RiskOperator actor, ReviewSavedSearch search) {
        boolean owner = search.getOwnerOperatorId().equals(actor.getId());
        boolean sharedManager = actor.getRole() == RiskOperatorRole.RISK_MANAGER
                && search.getVisibility() == SavedSearchVisibility.MERCHANT_SHARED;
        if (!owner && !sharedManager) {
            throw notFound();
        }
    }

    private RiskOperator liveActorForUpdate(RiskOperatorContext context) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantIdForUpdate(
                        context.operatorId(), context.merchantId())
                .filter(RiskOperator::isActive).orElseThrow(this::notFound);
        if (actor.getRole() != context.role()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Risk operator credential is stale");
        }
        return actor;
    }

    private void requireLiveActor(RiskOperatorContext context) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantId(
                        context.operatorId(), context.merchantId())
                .filter(RiskOperator::isActive).orElseThrow(this::notFound);
        if (actor.getRole() != context.role()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Risk operator credential is stale");
        }
    }

    private ReviewSavedSearch locked(UUID id, UUID merchantId) {
        return repository.findByIdAndMerchantIdForUpdate(id, merchantId).orElseThrow(this::notFound);
    }

    private SavedSearchView view(ReviewSavedSearch search, ReviewQueueFilter filter,
            Set<ReviewQueueColumn> columns) {
        return new SavedSearchView(search.getId(), search.getOwnerOperatorId(), search.getName(),
                search.getVisibility(), search.getFilterSchemaVersion(), ReviewQueueFilterView.from(filter),
                search.getSortCode(), columns.stream().sorted(java.util.Comparator.comparing(Enum::name)).toList(),
                search.getDefinitionDigest(),
                search.getLifecycleStatus(), search.getLifecycleVersion(), search.getCreatedAt(),
                search.getUpdatedAt(), search.getDisabledAt());
    }

    private Map<String, Object> safeState(ReviewSavedSearch search) {
        return Map.of("visibility", search.getVisibility().name(),
                "schemaVersion", search.getFilterSchemaVersion(),
                "sort", search.getSortCode().name(),
                "lifecycle", search.getLifecycleStatus().name(),
                "lifecycleVersion", search.getLifecycleVersion(),
                "definitionDigest", search.getDefinitionDigest());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved search was not found");
    }

    public record SavedSearchView(UUID id, UUID ownerOperatorId, String name,
            SavedSearchVisibility visibility, String filterSchemaVersion,
            ReviewQueueFilterView filter, ReviewQueueSort sort, List<ReviewQueueColumn> visibleColumns,
            String definitionDigest, SavedSearchLifecycleStatus lifecycleStatus,
            long lifecycleVersion, Instant createdAt, Instant updatedAt, Instant disabledAt) {
    }

    private record SearchDefinition(String name, SavedSearchVisibility visibility,
            ReviewQueueFilter filter, String canonicalFilterJson, ReviewQueueSort sort,
            Set<ReviewQueueColumn> columns, String columnsJson, String digest) {
    }
}
