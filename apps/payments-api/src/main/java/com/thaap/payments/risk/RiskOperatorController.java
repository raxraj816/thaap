package com.thaap.payments.risk;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/risk/operators")
public class RiskOperatorController {

    private final RiskOperatorService operatorService;
    private final IdempotencyService idempotencyService;

    public RiskOperatorController(RiskOperatorService operatorService, IdempotencyService idempotencyService) {
        this.operatorService = operatorService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/me")
    public RiskOperatorSessionResponse session(RiskOperatorContext context) {
        return new RiskOperatorSessionResponse(
                context.operatorId(), context.operatorCode(), context.role());
    }

    @PostMapping
    public ResponseEntity<RiskOperatorSecretResponse> create(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRiskOperatorRequest request, HttpServletRequest servletRequest) {
        RiskOperatorService.CreationResult result = operatorService.create(
                merchantContext, idempotencyKey, request.role(), requestId(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(RiskOperatorSecretResponse.from(result));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<RiskOperatorResponse> disable(MerchantContext merchantContext, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return idempotencyService.execute(merchantContext, idempotencyKey,
                "risk.operators.disable:" + id, new EmptyRequest(), RiskOperatorResponse.class,
                () -> ResponseEntity.ok(RiskOperatorResponse.from(
                        operatorService.disable(merchantContext, id, requestId(servletRequest)))));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record CreateRiskOperatorRequest(@NotNull RiskOperatorRole role) {
    }
    public record EmptyRequest() {
    }
    public record RiskOperatorSessionResponse(UUID id, String operatorCode,
            RiskOperatorRole role) {
    }
    public record RiskOperatorResponse(UUID id, String operatorCode, RiskOperatorRole role,
            RiskOperatorStatus status, long lifecycleEpoch, Instant createdAt, Instant disabledAt) {
        static RiskOperatorResponse from(RiskOperator operator) {
            return new RiskOperatorResponse(operator.getId(), operator.getOperatorCode(), operator.getRole(),
                    operator.getStatus(), operator.getLifecycleEpoch(), operator.getCreatedAt(),
                    operator.getDisabledAt());
        }
    }
    public record RiskOperatorSecretResponse(UUID id, String operatorCode, RiskOperatorRole role,
            RiskOperatorStatus status, String credentialPrefix, String credential) {
        static RiskOperatorSecretResponse from(RiskOperatorService.CreationResult result) {
            return new RiskOperatorSecretResponse(result.operator().getId(), result.operator().getOperatorCode(),
                    result.operator().getRole(), result.operator().getStatus(), result.credentialPrefix(),
                    result.rawSecret());
        }
    }
}
