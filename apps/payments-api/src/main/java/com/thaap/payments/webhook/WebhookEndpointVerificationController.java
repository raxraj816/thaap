package com.thaap.payments.webhook;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookEndpointVerificationController {

    private final WebhookEndpointVerificationService service;
    private final IdempotencyService idempotencyService;

    public WebhookEndpointVerificationController(WebhookEndpointVerificationService service,
            IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/webhook_endpoints/{endpointId}/verification_challenges")
    public ResponseEntity<WebhookEndpointVerificationService.ChallengeView> verify(
            MerchantContext context, @PathVariable UUID endpointId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            HttpServletRequest servletRequest) {
        return idempotencyService.executeDetached(context, key,
                "webhook.endpoint.verify:" + endpointId, Map.of(),
                WebhookEndpointVerificationService.ChallengeView.class,
                () -> ResponseEntity.ok(service.verify(context, endpointId, key,
                        requestId(servletRequest))));
    }

    @GetMapping("/webhook_endpoints/{endpointId}/verification_challenges/{challengeId}")
    public WebhookEndpointVerificationService.ChallengeView get(
            MerchantContext context, @PathVariable UUID endpointId,
            @PathVariable UUID challengeId) {
        return service.get(context, endpointId, challengeId);
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
