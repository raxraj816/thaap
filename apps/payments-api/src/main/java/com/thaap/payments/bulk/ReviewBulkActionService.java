package com.thaap.payments.bulk;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.investigation.WorkspaceCursorCodec;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.security.SensitiveValueHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewBulkActionService {

    private final ReviewBulkActionStore store;
    private final RiskOperatorRepository operatorRepository;
    private final SensitiveValueHasher hasher;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkspaceCursorCodec cursorCodec;
    private final Clock clock;

    public ReviewBulkActionService(ReviewBulkActionStore store,
            RiskOperatorRepository operatorRepository, SensitiveValueHasher hasher,
            ApplicationEventPublisher eventPublisher, WorkspaceCursorCodec cursorCodec,
            Clock clock) {
        this.store = store;
        this.operatorRepository = operatorRepository;
        this.hasher = hasher;
        this.eventPublisher = eventPublisher;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
    }

    @Transactional
    public ReviewBulkActionStore.RequestView start(RiskOperatorContext context,
            String idempotencyKey, StartRequest request) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantIdForUpdate(
                context.operatorId(), context.merchantId()).filter(RiskOperator::isActive)
                .orElseThrow(this::notFound);
        if (actor.getRole() != context.role()) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_OPERATOR_STALE);
        }
        validate(actor, idempotencyKey, request);
        List<ItemRequest> items = request.items().stream()
                .sorted(Comparator.comparing(ItemRequest::reviewCaseId)).toList();
        String requestDigest = digest(request.action(), request.targetOperatorId(), items);
        String identity = hasher.hmacSha256Keyring("review.bulk.idempotency", idempotencyKey)
                .getFirst().digest();
        ReviewBulkActionStore.RequestView existing = store.findByIdempotency(
                context.merchantId(), context.operatorId(), identity);
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.requestDigest().getBytes(StandardCharsets.US_ASCII),
                    requestDigest.getBytes(StandardCharsets.US_ASCII))) {
                throw new PublicApiException(
                        PublicApiErrorDescriptor.BULK_IDEMPOTENCY_MISMATCH);
            }
            return existing;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID requestId = UUID.randomUUID();
        store.insertRequest(requestId, context.merchantId(), context.operatorId(),
                request.action(), request.targetOperatorId(), identity, requestDigest,
                items.size(), now);
        int ordinal = 1;
        for (ItemRequest item : items) {
            store.insertItem(UUID.randomUUID(), context.merchantId(), requestId,
                    item.reviewCaseId(), item.expectedAssignmentVersion(), ordinal++, now);
        }
        eventPublisher.publishEvent(new ReviewBulkActionQueuedEvent(requestId));
        return store.find(context.merchantId(), requestId);
    }

    public ReviewBulkActionStore.RequestView get(RiskOperatorContext context, UUID requestId) {
        requireLive(context);
        ReviewBulkActionStore.RequestView view = store.find(context.merchantId(), requestId);
        if (view == null) throw notFound();
        return view;
    }

    public ItemPage items(RiskOperatorContext context, UUID requestId,
            Integer requestedLimit, String cursor) {
        get(context, requestId);
        int limit = requestedLimit == null ? 25 : requestedLimit;
        if (limit < 1 || limit > 25) {
            throw new PublicApiException(PublicApiErrorDescriptor.WORKSPACE_PAGE_SIZE_INVALID);
        }
        List<String> anchors = cursorCodec.decode("review-bulk-items", context.merchantId(),
                requestId, cursor, 1);
        int after = anchors == null ? 0 : parseOrdinal(anchors.getFirst());
        List<ReviewBulkActionStore.ItemView> rows = store.items(context.merchantId(),
                requestId, after, limit + 1);
        String next = null;
        if (rows.size() > limit) {
            next = cursorCodec.encode("review-bulk-items", context.merchantId(), requestId,
                    List.of(Integer.toString(rows.get(limit - 1).ordinal())));
            rows = List.copyOf(rows.subList(0, limit));
        }
        return new ItemPage(rows, next);
    }

    private int parseOrdinal(String value) {
        try {
            int ordinal = Integer.parseInt(value);
            if (ordinal < 0 || ordinal > 25) throw new NumberFormatException();
            return ordinal;
        } catch (NumberFormatException exception) {
            throw new PublicApiException(PublicApiErrorDescriptor.BULK_CURSOR_INVALID);
        }
    }

    private void validate(RiskOperator actor, String idempotencyKey, StartRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 255
                || idempotencyKey.codePoints().anyMatch(Character::isISOControl)) {
            throw new PublicApiException(
                    PublicApiErrorDescriptor.BULK_IDEMPOTENCY_KEY_INVALID);
        }
        if (request == null || request.action() == null || request.items() == null
                || request.items().isEmpty() || request.items().size() > 25) {
            throw new PublicApiException(PublicApiErrorDescriptor.BULK_ITEM_COUNT_INVALID);
        }
        if (actor.getRole() == RiskOperatorRole.VIEWER) {
            throw new PublicApiException(PublicApiErrorDescriptor.BULK_VIEWER_FORBIDDEN);
        }
        if (request.action() == ReviewBulkActionCode.ASSIGN_TO_OPERATOR) {
            if (actor.getRole() != RiskOperatorRole.RISK_MANAGER
                    || request.targetOperatorId() == null) {
                throw new PublicApiException(PublicApiErrorDescriptor.BULK_MANAGER_REQUIRED);
            }
        } else if (request.targetOperatorId() != null) {
            throw new PublicApiException(PublicApiErrorDescriptor.BULK_TARGET_INVALID);
        }
        Set<UUID> identities = new HashSet<>();
        for (ItemRequest item : request.items()) {
            if (item == null || item.reviewCaseId() == null
                    || item.expectedAssignmentVersion() < 0
                    || !identities.add(item.reviewCaseId())) {
                throw new PublicApiException(PublicApiErrorDescriptor.BULK_ITEM_INVALID);
            }
        }
    }

    private void requireLive(RiskOperatorContext context) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantId(
                context.operatorId(), context.merchantId()).filter(RiskOperator::isActive)
                .orElseThrow(this::notFound);
        if (actor.getRole() != context.role()) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_OPERATOR_STALE);
        }
    }

    private String digest(ReviewBulkActionCode action, UUID target, List<ItemRequest> items) {
        try {
            StringBuilder source = new StringBuilder(action.name()).append('\n')
                    .append(target == null ? "-" : target).append('\n');
            for (ItemRequest item : items) {
                source.append(item.reviewCaseId()).append(':')
                        .append(item.expectedAssignmentVersion()).append('\n');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ResponseStatusException notFound() {
        return new PublicApiException(PublicApiErrorDescriptor.BULK_ACTION_NOT_FOUND);
    }

    public record StartRequest(ReviewBulkActionCode action, UUID targetOperatorId,
            List<ItemRequest> items) {
    }

    public record ItemRequest(UUID reviewCaseId, long expectedAssignmentVersion) {
    }

    public record ItemPage(List<ReviewBulkActionStore.ItemView> data,
            String nextCursor) {
    }
}
