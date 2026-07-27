package com.thaap.payments.developer;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/developer")
public class DeveloperScenarioController {

    private final DeveloperScenarioService service;
    private final IdempotencyService idempotencyService;

    public DeveloperScenarioController(DeveloperScenarioService service,
            IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/test-cards")
    public DeveloperScenarioService.CatalogueView catalogue() {
        return service.catalogue();
    }

    @PostMapping("/scenario-runs")
    public ResponseEntity<DeveloperScenarioService.RunView> create(MerchantContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRunRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey, "developer_scenarios.create",
                request, DeveloperScenarioService.RunView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(
                        context, request.scenarioType(), request.seed(), requestId(servletRequest))));
    }

    @GetMapping("/scenario-runs/{runId}")
    public DeveloperScenarioService.RunView get(MerchantContext context, @PathVariable UUID runId) {
        return service.get(context, runId);
    }

    @PostMapping("/scenario-runs/{runId}/claims")
    public ResponseEntity<DeveloperScenarioService.RunView> claim(MerchantContext context,
            @PathVariable UUID runId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ClaimRequest request, HttpServletRequest servletRequest) {
        requireScenarioRunner(context);
        return idempotencyService.execute(context, idempotencyKey,
                "developer_scenarios.claim:" + runId, request,
                DeveloperScenarioService.RunView.class,
                () -> ResponseEntity.ok(service.claim(
                        context, runId, request.workerId(), requestId(servletRequest))));
    }

    @PostMapping("/scenario-runs/{runId}/steps/{stepIndex}/complete")
    public ResponseEntity<DeveloperScenarioService.RunView> completeStep(MerchantContext context,
            @PathVariable UUID runId, @PathVariable @Min(0) int stepIndex,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CompleteStepRequest request, HttpServletRequest servletRequest) {
        requireScenarioRunner(context);
        return idempotencyService.execute(context, idempotencyKey,
                "developer_scenarios.complete:" + runId + ":" + stepIndex, request,
                DeveloperScenarioService.RunView.class,
                () -> ResponseEntity.ok(service.completeStep(context, runId, stepIndex,
                        request.workerId(), request.leaseEpoch(), request.resourceId(),
                        requestId(servletRequest))));
    }

    @PostMapping("/scenario-runs/{runId}/fail")
    public ResponseEntity<DeveloperScenarioService.RunView> fail(MerchantContext context,
            @PathVariable UUID runId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FailRunRequest request, HttpServletRequest servletRequest) {
        requireScenarioRunner(context);
        return idempotencyService.execute(context, idempotencyKey,
                "developer_scenarios.fail:" + runId, request,
                DeveloperScenarioService.RunView.class,
                () -> ResponseEntity.ok(service.fail(context, runId, request.workerId(),
                        request.leaseEpoch(), request.failureCode(), requestId(servletRequest))));
    }

    @GetMapping("/scenario-runs/{runId}/report")
    public DeveloperScenarioService.ReportView report(MerchantContext context,
            @PathVariable UUID runId) {
        return service.report(context, runId);
    }

    private static String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unavailable" : requestId.toString();
    }

    private static void requireScenarioRunner(MerchantContext context) {
        if (context.trafficOrigin() != TrafficOrigin.SYNTHETIC_SCENARIO) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Trusted scenario runner credential is required");
        }
    }

    public record CreateRunRequest(@NotNull DeveloperScenarioType scenarioType,
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String seed) {
    }

    public record ClaimRequest(@NotNull UUID workerId) {
    }

    public record CompleteStepRequest(@NotNull UUID workerId,
            @Min(1) long leaseEpoch, @NotNull UUID resourceId) {
    }

    public record FailRunRequest(@NotNull UUID workerId,
            @Min(1) long leaseEpoch, @NotNull DeveloperScenarioFailureCode failureCode) {
    }
}
