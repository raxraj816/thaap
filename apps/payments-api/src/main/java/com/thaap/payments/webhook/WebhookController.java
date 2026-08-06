package com.thaap.payments.webhook;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.validator.constraints.URL;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
public class WebhookController {

    private final WebhookEndpointService endpointService;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookEventRepository eventRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;

    public WebhookController(WebhookEndpointService endpointService,
            WebhookDeliveryRepository deliveryRepository,
            WebhookEventRepository eventRepository,
            WebhookDeliveryAttemptRepository attemptRepository) {
        this.endpointService = endpointService;
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.attemptRepository = attemptRepository;
    }

    @PostMapping("/webhook_endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointResponse createEndpoint(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateWebhookEndpointRequest request, HttpServletRequest httpServletRequest) {
        WebhookEndpointService.EndpointSecretResult result = endpointService.create(
                merchantContext, idempotencyKey, request.url(), request.enabledEvents(),
                requestIdFrom(httpServletRequest));
        return WebhookEndpointResponse.from(result);
    }

    @PostMapping("/webhook_endpoints/{id}/rotate_secret")
    public WebhookEndpointResponse rotateSecret(MerchantContext merchantContext, @PathVariable UUID id,
            HttpServletRequest httpServletRequest) {
        return WebhookEndpointResponse.from(endpointService.rotateSecret(
                merchantContext, id, requestIdFrom(httpServletRequest)));
    }

    @PostMapping("/webhook_endpoints/{id}/disable")
    public WebhookEndpointResponse disableEndpoint(MerchantContext merchantContext, @PathVariable UUID id,
            HttpServletRequest httpServletRequest) {
        WebhookEndpointService.EndpointStateResult result = endpointService.disable(
                merchantContext, id, requestIdFrom(httpServletRequest));
        return WebhookEndpointResponse.withoutSecret(result);
    }

    @GetMapping("/webhook_deliveries")
    public List<WebhookDeliveryResponse> listDeliveries(MerchantContext merchantContext,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) Instant beforeCreatedAt,
            @RequestParam(required = false) UUID beforeId) {
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAGINATION_BEFORE_CURSOR_INCOMPLETE, "beforeCreatedAt and beforeId must be supplied together");
        }
        List<WebhookDelivery> deliveries = beforeCreatedAt == null
                ? deliveryRepository.findByMerchantIdOrderByCreatedAtDescIdDesc(
                        merchantContext.merchantId(), PageRequest.of(0, limit))
                : deliveryRepository.findPageBefore(merchantContext.merchantId(),
                        beforeCreatedAt, beforeId, PageRequest.of(0, limit));
        return deliveries
                .stream()
                .map(WebhookDeliveryResponse::from)
                .toList();
    }

    @GetMapping("/webhook_deliveries/{id}")
    public WebhookDeliveryDetailResponse getDelivery(MerchantContext merchantContext, @PathVariable UUID id) {
        WebhookDelivery delivery = loadDelivery(merchantContext, id);
        WebhookEvent event = eventRepository.findByIdAndMerchantId(
                        delivery.getWebhookEventId(), merchantContext.merchantId())
                .orElseThrow(() -> new IllegalStateException("Webhook event lineage is missing"));
        return WebhookDeliveryDetailResponse.from(delivery, event);
    }

    @GetMapping("/webhook_deliveries/{id}/attempts")
    public List<WebhookAttemptResponse> listAttempts(MerchantContext merchantContext, @PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        loadDelivery(merchantContext, id);
        return attemptRepository.findByMerchantIdAndWebhookDeliveryIdOrderByAttemptNumberDesc(
                        merchantContext.merchantId(), id, PageRequest.of(0, limit))
                .stream()
                .map(WebhookAttemptResponse::from)
                .toList();
    }

    private WebhookDelivery loadDelivery(MerchantContext merchantContext, UUID id) {
        return deliveryRepository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.WEBHOOK_DELIVERY_NOT_FOUND, "Webhook delivery was not found"));
    }

    private String requestIdFrom(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null || requestId.toString().isBlank()) {
            return "unknown";
        }
        return requestId.toString();
    }

    public record CreateWebhookEndpointRequest(@NotBlank @URL String url, Set<String> enabledEvents) {
    }

    public record WebhookEndpointResponse(UUID id, UUID merchantId, String url,
            WebhookEndpointStatus operationalStatus, boolean active, String signingSecretPrefix,
            String previousSigningSecretPrefix, Instant previousSigningSecretExpiresAt,
            String signingSecret, Set<String> enabledEvents,
            WebhookEndpointVerificationStatus verificationStatus,
            Instant verifiedAt) {

        static WebhookEndpointResponse from(WebhookEndpointService.EndpointSecretResult result) {
            WebhookEndpoint endpoint = result.endpoint();
            return new WebhookEndpointResponse(endpoint.getId(), endpoint.getMerchantId(), endpoint.getUrl(),
                    endpoint.getOperationalStatus(), endpoint.isActive(), endpoint.getSigningSecretPrefix(),
                    endpoint.getPreviousSigningSecretPrefix(),
                    endpoint.getPreviousSigningSecretExpiresAt(),
                    result.rawSigningSecret(), result.enabledEvents(),
                    endpoint.getVerificationStatus(), endpoint.getVerifiedAt());
        }

        static WebhookEndpointResponse withoutSecret(WebhookEndpointService.EndpointStateResult result) {
            WebhookEndpoint endpoint = result.endpoint();
            return new WebhookEndpointResponse(endpoint.getId(), endpoint.getMerchantId(), endpoint.getUrl(),
                    endpoint.getOperationalStatus(), endpoint.isActive(), endpoint.getSigningSecretPrefix(),
                    endpoint.getPreviousSigningSecretPrefix(),
                    endpoint.getPreviousSigningSecretExpiresAt(),
                    null, result.enabledEvents(), endpoint.getVerificationStatus(),
                    endpoint.getVerifiedAt());
        }
    }

    public record WebhookDeliveryResponse(UUID id, UUID merchantId, UUID webhookEndpointId, UUID webhookEventId,
            String eventType, UUID resourceId, WebhookDeliveryStatus status, int attemptCount,
            int deliveryGeneration, int generationAttemptCount,
            Instant nextAttemptAt, Instant lastAttemptAt, Instant deliveredAt,
            String lastError, WebhookDeliveryErrorCode lastErrorCode, Instant createdAt) {

        static WebhookDeliveryResponse from(WebhookDelivery delivery) {
            return new WebhookDeliveryResponse(delivery.getId(), delivery.getMerchantId(),
                    delivery.getWebhookEndpointId(), delivery.getWebhookEventId(), delivery.getEventType(),
                    delivery.getResourceId(), delivery.getStatus(), delivery.getAttemptCount(),
                    delivery.getDeliveryGeneration(), delivery.getGenerationAttemptCount(),
                    delivery.getNextAttemptAt(), delivery.getLastAttemptAt(), delivery.getDeliveredAt(),
                    delivery.getLastErrorCode() == null ? null : delivery.getLastErrorCode().name(),
                    delivery.getLastErrorCode(), delivery.getCreatedAt());
        }
    }

    public record WebhookDeliveryDetailResponse(WebhookDeliveryResponse delivery, UUID eventId,
            String eventType, UUID resourceId, String resourceType, String apiVersion,
            com.thaap.payments.tenant.TrafficOrigin trafficOrigin, Instant occurredAt) {

        static WebhookDeliveryDetailResponse from(WebhookDelivery delivery, WebhookEvent event) {
            return new WebhookDeliveryDetailResponse(WebhookDeliveryResponse.from(delivery), event.getId(),
                    event.getEventType(), event.getResourceId(), event.getResourceType(), event.getApiVersion(),
                    event.getTrafficOrigin(), event.getOccurredAt());
        }
    }

    public record WebhookAttemptResponse(UUID id, int attemptNumber, WebhookAttemptOutcome outcome,
            int deliveryGeneration, int generationAttemptNumber, String dispatchSource,
            Boolean httpRequestSent, Instant dispatchStartedAt, Integer responseStatus,
            WebhookDeliveryErrorCode errorCode, Instant startedAt, Instant completedAt, Long latencyMs) {

        static WebhookAttemptResponse from(WebhookDeliveryAttempt attempt) {
            return new WebhookAttemptResponse(attempt.getId(), attempt.getAttemptNumber(), attempt.getOutcome(),
                    attempt.getDeliveryGeneration(), attempt.getGenerationAttemptNumber(),
                    attempt.getDispatchSource(),
                    attempt.getHttpRequestSent(), attempt.getDispatchStartedAt(), attempt.getResponseStatus(),
                    attempt.getErrorCode(), attempt.getStartedAt(), attempt.getCompletedAt(), attempt.getLatencyMs());
        }
    }
}
