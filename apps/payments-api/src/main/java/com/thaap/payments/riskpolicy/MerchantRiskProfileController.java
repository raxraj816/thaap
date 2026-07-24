package com.thaap.payments.riskpolicy;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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
@RequestMapping("/risk/profiles")
public class MerchantRiskProfileController {

    private final MerchantRiskPolicyManagementService managementService;
    private final IdempotencyService idempotencyService;

    public MerchantRiskProfileController(MerchantRiskPolicyManagementService managementService,
            IdempotencyService idempotencyService) {
        this.managementService = managementService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public List<MerchantRiskPolicyManagementService.ProfileView> list(RiskOperatorContext context) {
        return managementService.listProfiles(context);
    }

    @PostMapping
    public ResponseEntity<MerchantRiskPolicyManagementService.ProfileView> create(RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateProfileRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey, "risk.profiles.create",
                request, MerchantRiskPolicyManagementService.ProfileView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(managementService.createProfile(
                        context, request.riskTier(), request.reasonCode(), requestId(servletRequest))));
    }

    @PostMapping("/{profileId}/submit")
    public ResponseEntity<MerchantRiskPolicyManagementService.ProfileView> submit(RiskOperatorContext context,
            @PathVariable UUID profileId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return transition(context, profileId, idempotencyKey, request, servletRequest,
                "submit", MerchantRiskLifecycleState.PENDING_APPROVAL);
    }

    @PostMapping("/{profileId}/approve")
    public ResponseEntity<MerchantRiskPolicyManagementService.ProfileView> approve(RiskOperatorContext context,
            @PathVariable UUID profileId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return transition(context, profileId, idempotencyKey, request, servletRequest,
                "approve", MerchantRiskLifecycleState.APPROVED);
    }

    @PostMapping("/{profileId}/activate")
    public ResponseEntity<MerchantRiskPolicyManagementService.ProfileView> activate(RiskOperatorContext context,
            @PathVariable UUID profileId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return transition(context, profileId, idempotencyKey, request, servletRequest,
                "activate", MerchantRiskLifecycleState.ACTIVE);
    }

    @PostMapping("/{profileId}/retire")
    public ResponseEntity<MerchantRiskPolicyManagementService.ProfileView> retire(RiskOperatorContext context,
            @PathVariable UUID profileId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return transition(context, profileId, idempotencyKey, request, servletRequest,
                "retire", MerchantRiskLifecycleState.RETIRED);
    }

    private ResponseEntity<MerchantRiskPolicyManagementService.ProfileView> transition(
            RiskOperatorContext context, UUID profileId, String idempotencyKey, EmptyRequest request,
            HttpServletRequest servletRequest, String operation, MerchantRiskLifecycleState target) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.profiles." + operation + ":" + profileId, request,
                MerchantRiskPolicyManagementService.ProfileView.class,
                () -> ResponseEntity.ok(managementService.transitionProfile(
                        context, profileId, target, requestId(servletRequest))));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record CreateProfileRequest(@NotNull MerchantRiskTier riskTier,
            @NotNull MerchantRiskProfileReasonCode reasonCode) { }

    public record EmptyRequest() { }
}
