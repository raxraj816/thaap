package com.thaap.payments.riskpolicy;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/risk/policies")
public class MerchantRiskPolicyController {

    private final MerchantRiskPolicyManagementService managementService;
    private final IdempotencyService idempotencyService;

    public MerchantRiskPolicyController(MerchantRiskPolicyManagementService managementService,
            IdempotencyService idempotencyService) {
        this.managementService = managementService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public List<MerchantRiskPolicyManagementService.PolicyView> list(RiskOperatorContext context) {
        return managementService.listPolicies(context);
    }

    @PostMapping
    public ResponseEntity<MerchantRiskPolicyManagementService.PolicyView> create(RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePolicyRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey, "risk.policies.create",
                request, MerchantRiskPolicyManagementService.PolicyView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(managementService.createPolicy(
                        context, request.code(), request.displayName(), requestId(servletRequest))));
    }

    @PostMapping("/{policyId}/thresholds")
    public ResponseEntity<MerchantRiskPolicyManagementService.ThresholdView> addThreshold(
            RiskOperatorContext context, @PathVariable UUID policyId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateThresholdRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.policies.threshold.create:" + policyId, request,
                MerchantRiskPolicyManagementService.ThresholdView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(managementService.addThreshold(
                        context, policyId, request.code(), request.featureCode(), request.operator(),
                        request.longValue(), request.booleanValue(), request.stringValues(),
                        request.candidateAction(), request.priority(), request.reasonCode(),
                        request.currencyScope(), requestId(servletRequest))));
    }

    @PostMapping("/{policyId}/submit")
    public ResponseEntity<MerchantRiskPolicyManagementService.PolicyView> submit(RiskOperatorContext context,
            @PathVariable UUID policyId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return transition(context, policyId, idempotencyKey, request, servletRequest,
                "submit", MerchantRiskLifecycleState.PENDING_APPROVAL);
    }

    @PostMapping("/{policyId}/approve")
    public ResponseEntity<MerchantRiskPolicyManagementService.PolicyView> approve(RiskOperatorContext context,
            @PathVariable UUID policyId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return transition(context, policyId, idempotencyKey, request, servletRequest,
                "approve", MerchantRiskLifecycleState.APPROVED);
    }

    @PostMapping("/{policyId}/activate")
    public ResponseEntity<MerchantRiskPolicyManagementService.PolicyView> activate(RiskOperatorContext context,
            @PathVariable UUID policyId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return transition(context, policyId, idempotencyKey, request, servletRequest,
                "activate", MerchantRiskLifecycleState.ACTIVE);
    }

    @PostMapping("/{policyId}/retire")
    public ResponseEntity<MerchantRiskPolicyManagementService.PolicyView> retire(RiskOperatorContext context,
            @PathVariable UUID policyId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return transition(context, policyId, idempotencyKey, request, servletRequest,
                "retire", MerchantRiskLifecycleState.RETIRED);
    }

    @PostMapping("/{policyId}/preview")
    public MerchantRiskPolicyManagementService.PreviewView preview(RiskOperatorContext context,
            @PathVariable UUID policyId, @Valid @RequestBody PreviewRequest request) {
        return managementService.preview(context, policyId, request.features(), request.currency());
    }

    private ResponseEntity<MerchantRiskPolicyManagementService.PolicyView> transition(
            RiskOperatorContext context, UUID policyId, String idempotencyKey, EmptyRequest request,
            HttpServletRequest servletRequest, String operation, MerchantRiskLifecycleState target) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.policies." + operation + ":" + policyId, request,
                MerchantRiskPolicyManagementService.PolicyView.class,
                () -> ResponseEntity.ok(managementService.transitionPolicy(
                        context, policyId, target, requestId(servletRequest))));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record CreatePolicyRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String code,
            @NotBlank @Size(max = 128) String displayName) { }

    public record CreateThresholdRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String code,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
            @Size(max = 128) String featureCode,
            @NotNull PolicyThresholdOperator operator,
            @Min(0) Long longValue,
            Boolean booleanValue,
            @Size(max = 32) List<@Pattern(regexp = "[A-Z0-9_]{1,64}") String> stringValues,
            @NotNull FraudDecisionAction candidateAction,
            @Min(0) @Max(1000) int priority,
            @NotNull PolicyThresholdReasonCode reasonCode,
            @Pattern(regexp = "USD|EUR|GBP|INR") String currencyScope) { }

    public record PreviewRequest(@NotNull @Size(max = 32) Map<String, Object> features,
            @Pattern(regexp = "USD|EUR|GBP|INR") String currency) { }

    public record EmptyRequest() { }
}
