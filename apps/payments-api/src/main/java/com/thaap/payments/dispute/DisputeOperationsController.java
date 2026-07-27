package com.thaap.payments.dispute;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DisputeOperationsController {

    private final DisputeOperationsService service;
    private final IdempotencyService idempotencyService;

    public DisputeOperationsController(DisputeOperationsService service,
            IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/risk/disputes")
    public List<DisputeOperationsService.WorkflowView> list(RiskOperatorContext context,
            @RequestParam(required = false) DisputeWorkflowStatus status,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        return service.list(context, status, limit);
    }

    @GetMapping("/risk/disputes/{chargebackId}")
    public DisputeOperationsService.WorkflowView get(RiskOperatorContext context,
            @PathVariable UUID chargebackId) {
        return service.get(context, chargebackId);
    }

    @PostMapping("/risk/disputes/{chargebackId}/evidence-versions")
    public ResponseEntity<DisputeOperationsService.WorkflowView> revise(RiskOperatorContext context,
            @PathVariable UUID chargebackId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody EvidenceRevisionRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.executeAuthorized(context, key,
                "risk.disputes.evidence.revise:" + chargebackId, request,
                DisputeOperationsService.WorkflowView.class,
                () -> service.authorizeEvidenceRevisionReplay(context, chargebackId),
                () -> ResponseEntity.ok(service.reviseEvidence(context, chargebackId,
                        request.deliveryConfirmed(), request.customerCommunicationPresent(),
                        request.deviceContinuity(), requestId(servletRequest))));
    }

    @PostMapping("/risk/disputes/{chargebackId}/submit")
    public ResponseEntity<DisputeOperationsService.WorkflowView> submit(RiskOperatorContext context,
            @PathVariable UUID chargebackId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody SubmitRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.executeAuthorized(context, key,
                "risk.disputes.submit:" + chargebackId,
                request, DisputeOperationsService.WorkflowView.class,
                () -> service.authorizeSubmissionReplay(context, chargebackId),
                () -> ResponseEntity.ok(service.submit(context, chargebackId,
                        request.expectedEvidenceVersion(), requestId(servletRequest))));
    }

    @PostMapping("/simulator/disputes/{chargebackId}/outcomes")
    public ResponseEntity<DisputeOperationsService.OutcomeView> outcome(MerchantContext context,
            @PathVariable UUID chargebackId,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody OutcomeRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, key, "simulator.disputes.outcome:" + chargebackId,
                request, DisputeOperationsService.OutcomeView.class,
                () -> ResponseEntity.ok(service.recordOutcome(context, chargebackId,
                        request.externalEventKey(), request.outcome(), request.occurredAt(),
                        requestId(servletRequest))));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    public record EvidenceRevisionRequest(boolean deliveryConfirmed,
            boolean customerCommunicationPresent, boolean deviceContinuity) { }

    public record SubmitRequest(@Min(1) int expectedEvidenceVersion) { }

    public record OutcomeRequest(
            @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String externalEventKey,
            @NotNull DisputeOutcome outcome,
            @NotNull Instant occurredAt) { }
}
