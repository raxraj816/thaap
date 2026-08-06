package com.thaap.payments.webhook;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorCredential;
import com.thaap.payments.risk.RiskOperatorCredentialRepository;
import com.thaap.payments.risk.RiskOperatorRepository;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.tenant.MerchantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebhookOperationsService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;
    private final WebhookOperationsMetrics metrics;
    private final RiskOperatorRepository operatorRepository;
    private final RiskOperatorCredentialRepository credentialRepository;
    private final Clock clock;
    private final RiskOperationsEventProducer eventProducer;

    public WebhookOperationsService(WebhookDeliveryRepository deliveryRepository,
            WebhookEndpointRepository endpointRepository, JdbcTemplate jdbcTemplate,
            AuditLogService auditLogService, WebhookOperationsMetrics metrics,
            RiskOperatorRepository operatorRepository,
            RiskOperatorCredentialRepository credentialRepository, Clock clock,
            RiskOperationsEventProducer eventProducer) {
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
        this.metrics = metrics;
        this.operatorRepository = operatorRepository;
        this.credentialRepository = credentialRepository;
        this.clock = clock;
        this.eventProducer = eventProducer;
    }

    @Transactional(readOnly = true)
    public List<DeadLetterView> deadLetters(RiskOperatorContext context, String eventType,
            WebhookDeliveryErrorCode errorCode, Instant createdAfter, Instant createdBefore,
            Instant cursorCreatedAt, UUID cursorId, int limit) {
        requireManager(context);
        requireCursorPair(cursorCreatedAt, cursorId);
        if (createdAfter != null && createdBefore != null && !createdAfter.isBefore(createdBefore)) {
            throw new PublicApiException(PublicApiErrorDescriptor.CREATED_RANGE_INVALID, "createdAfter must be before createdBefore");
        }
        return deliveryRepository.findDeadLetters(context.merchantId(), eventType != null, eventType,
                        errorCode != null, errorCode, createdAfter != null, createdAfter,
                        createdBefore != null, createdBefore, cursorCreatedAt != null,
                        cursorCreatedAt, cursorId, PageRequest.of(0, limit))
                .stream().map(DeadLetterView::from).toList();
    }

    @Transactional
    public RedeliveryView redeliver(MerchantContext context, UUID deliveryId,
            WebhookRedeliveryReason reason, String requestId) {
        RedeliveryView view = redeliver(context.merchantId(), deliveryId, reason, "MERCHANT_API", null,
                "MERCHANT", requestId, () -> auditMerchant(context, deliveryId, requestId));
        eventProducer.webhookRedeliveryRequested(context, view.endpointId(), view.createdAt(),
                requestId, view.requestedGeneration(), reason.name());
        return view;
    }

    @Transactional
    public RedeliveryView redeliver(RiskOperatorContext context, UUID deliveryId,
            WebhookRedeliveryReason reason, String requestId) {
        RiskOperator actor = liveManagerForUpdate(context);
        RedeliveryView view = redeliver(context.merchantId(), deliveryId, reason, "RISK_OPERATOR",
                actor.getId(), actor.getRole().name(), requestId,
                () -> auditOperator(context, deliveryId, requestId));
        eventProducer.webhookRedeliveryRequested(context, view.endpointId(), view.createdAt(),
                requestId, view.requestedGeneration(), reason.name());
        return view;
    }

    @Transactional
    public void authorizeOperatorReplay(RiskOperatorContext context, UUID deliveryId) {
        liveManagerForUpdate(context);
        if (deliveryRepository.findByIdAndMerchantId(deliveryId, context.merchantId()).isEmpty()) {
            throw notFound();
        }
    }

    private RedeliveryView redeliver(UUID merchantId, UUID deliveryId,
            WebhookRedeliveryReason reason, String actorType, UUID operatorId,
            String actorRole, String requestId, Runnable audit) {
        WebhookDelivery snapshot = deliveryRepository.findByIdAndMerchantId(deliveryId, merchantId)
                .orElseThrow(WebhookOperationsService::notFound);
        WebhookEndpoint endpoint = endpointRepository.findByIdAndMerchantIdForUpdate(
                        snapshot.getWebhookEndpointId(), merchantId)
                .orElseThrow(WebhookOperationsService::notFound);
        WebhookDelivery delivery = deliveryRepository.findByIdAndMerchantIdForUpdate(deliveryId, merchantId)
                .orElseThrow(WebhookOperationsService::notFound);
        if (!endpoint.getId().equals(delivery.getWebhookEndpointId())) {
            throw new PublicApiException(PublicApiErrorDescriptor.WEBHOOK_DELIVERY_ENDPOINT_CHANGED, "Webhook delivery endpoint changed while redelivery was requested");
        }
        if (!endpoint.isActive()) {
            throw new PublicApiException(PublicApiErrorDescriptor.WEBHOOK_ENDPOINT_INACTIVE_FOR_REDELIVERY, "Webhook endpoint must be active for redelivery");
        }
        int requestedGeneration;
        try {
            requestedGeneration = delivery.scheduleManualRedelivery();
        } catch (IllegalStateException exception) {
            throw new PublicApiException(PublicApiErrorDescriptor.WEBHOOK_DELIVERY_NOT_TERMINAL, "Webhook delivery generation must be terminal before redelivery");
        }
        UUID redeliveryRequestId = UUID.randomUUID();
        Instant now = clock.instant();
        String digest = SecretHasher.sha256(String.join("|", deliveryId.toString(),
                delivery.getWebhookEventId().toString(), endpoint.getId().toString(),
                Integer.toString(requestedGeneration), actorType,
                operatorId == null ? "MERCHANT" : operatorId.toString(), reason.name()));
        jdbcTemplate.update("""
                INSERT INTO webhook_redelivery_requests
                    (id, merchant_id, webhook_delivery_id, webhook_event_id,
                     webhook_endpoint_id, requested_generation, actor_type,
                     actor_operator_id, actor_role, request_reason, request_digest,
                     lifecycle_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?)
                """, redeliveryRequestId, merchantId, deliveryId,
                delivery.getWebhookEventId(), endpoint.getId(), requestedGeneration,
                actorType, operatorId, actorRole, reason.name(), digest, Timestamp.from(now));
        audit.run();
        metrics.record("MANUAL_REDELIVERY", "SCHEDULED", actorType);
        return new RedeliveryView(redeliveryRequestId, deliveryId, delivery.getWebhookEventId(),
                endpoint.getId(), requestedGeneration, reason, "SCHEDULED", now);
    }

    private void auditMerchant(MerchantContext context, UUID deliveryId, String requestId) {
        auditLogService.recordMerchantApiAction(context, requestId, "webhook.redelivery.requested",
                "webhook_delivery", deliveryId, "Authorized manual webhook redelivery",
                null, Map.of("source", "MERCHANT_API"));
    }

    private void auditOperator(RiskOperatorContext context, UUID deliveryId, String requestId) {
        auditLogService.recordRiskOperatorAction(context, requestId, "webhook.redelivery.requested",
                "webhook_delivery", deliveryId, "Authorized manual webhook redelivery",
                null, Map.of("source", "RISK_OPERATOR", "role", context.role().name()));
    }

    private static void requireManager(RiskOperatorContext context) {
        if (context.role() != RiskOperatorRole.RISK_MANAGER) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_MANAGER_ROLE_REQUIRED, "RISK_MANAGER role is required");
        }
    }

    private RiskOperator liveManagerForUpdate(RiskOperatorContext context) {
        RiskOperator actor = operatorRepository.findByIdAndMerchantIdForUpdate(
                        context.operatorId(), context.merchantId())
                .filter(RiskOperator::isActive)
                .orElseThrow(WebhookOperationsService::riskResourceNotFound);
        RiskOperatorCredential credential = credentialRepository
                .findByMerchantIdAndRiskOperatorIdForUpdate(
                        context.merchantId(), context.operatorId())
                .filter(candidate -> candidate.getDisabledAt() == null)
                .orElseThrow(WebhookOperationsService::riskResourceNotFound);
        if (!credential.getRiskOperatorId().equals(actor.getId())
                || actor.getRole() != context.role()) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_OPERATOR_STALE, "Risk operator credential is stale");
        }
        if (actor.getRole() != RiskOperatorRole.RISK_MANAGER) {
            throw new PublicApiException(PublicApiErrorDescriptor.RISK_MANAGER_ROLE_REQUIRED, "RISK_MANAGER role is required");
        }
        return actor;
    }

    private static ResponseStatusException riskResourceNotFound() {
        return new PublicApiException(PublicApiErrorDescriptor.RISK_RESOURCE_NOT_FOUND, "Risk resource was not found");
    }

    private static void requireCursorPair(Instant cursorCreatedAt, UUID cursorId) {
        if ((cursorCreatedAt == null) != (cursorId == null)) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAGINATION_CURSOR_INCOMPLETE, "cursorCreatedAt and cursorId must be supplied together");
        }
    }

    private static ResponseStatusException notFound() {
        return new PublicApiException(PublicApiErrorDescriptor.WEBHOOK_DELIVERY_NOT_FOUND, "Webhook delivery was not found");
    }

    public record DeadLetterView(UUID id, UUID webhookEndpointId, UUID webhookEventId,
            String eventType, WebhookDeliveryErrorCode errorCode, int attemptCount,
            int deliveryGeneration, int generationAttemptCount, Instant createdAt,
            Instant lastAttemptAt) {

        static DeadLetterView from(WebhookDelivery delivery) {
            return new DeadLetterView(delivery.getId(), delivery.getWebhookEndpointId(),
                    delivery.getWebhookEventId(), delivery.getEventType(),
                    delivery.getLastErrorCode(), delivery.getAttemptCount(),
                    delivery.getDeliveryGeneration(), delivery.getGenerationAttemptCount(),
                    delivery.getCreatedAt(), delivery.getLastAttemptAt());
        }
    }

    public record RedeliveryView(UUID requestId, UUID deliveryId, UUID eventId,
            UUID endpointId, int requestedGeneration, WebhookRedeliveryReason reason,
            String lifecycleStatus, Instant createdAt) { }
}
