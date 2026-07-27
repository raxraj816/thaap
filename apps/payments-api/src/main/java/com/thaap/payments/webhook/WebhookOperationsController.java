package com.thaap.payments.webhook;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookOperationsController {

    private final WebhookOperationsService service;
    private final IdempotencyService idempotencyService;

    public WebhookOperationsController(WebhookOperationsService service,
            IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/risk/webhook-dead-letters")
    public List<WebhookOperationsService.DeadLetterView> deadLetters(
            RiskOperatorContext context,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) WebhookDeliveryErrorCode errorCode,
            @RequestParam(required = false) Instant createdAfter,
            @RequestParam(required = false) Instant createdBefore,
            @RequestParam(required = false) Instant cursorCreatedAt,
            @RequestParam(required = false) UUID cursorId,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        return service.deadLetters(context, eventType, errorCode, createdAfter, createdBefore,
                cursorCreatedAt, cursorId, limit);
    }

    @PostMapping("/webhook_deliveries/{deliveryId}/redeliver")
    public ResponseEntity<WebhookOperationsService.RedeliveryView> merchantRedeliver(
            MerchantContext context, @PathVariable UUID deliveryId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RedeliveryRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, key,
                "webhook.delivery.redeliver:" + deliveryId, request,
                WebhookOperationsService.RedeliveryView.class,
                () -> ResponseEntity.ok(service.redeliver(context, deliveryId,
                        request.reason(), requestId(servletRequest))));
    }

    @PostMapping("/risk/webhook-deliveries/{deliveryId}/redeliver")
    public ResponseEntity<WebhookOperationsService.RedeliveryView> operatorRedeliver(
            RiskOperatorContext context, @PathVariable UUID deliveryId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RedeliveryRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.executeAuthorized(context, key,
                "risk.webhook.delivery.redeliver:" + deliveryId, request,
                WebhookOperationsService.RedeliveryView.class,
                () -> service.authorizeOperatorReplay(context, deliveryId),
                () -> ResponseEntity.ok(service.redeliver(context, deliveryId,
                        request.reason(), requestId(servletRequest))));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    public record RedeliveryRequest(@NotNull WebhookRedeliveryReason reason) { }
}
