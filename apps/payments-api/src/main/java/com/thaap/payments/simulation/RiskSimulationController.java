package com.thaap.payments.simulation;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
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
@RequestMapping("/risk/simulations")
public class RiskSimulationController {

    private final RiskSimulationService service;
    private final IdempotencyService idempotencyService;

    public RiskSimulationController(RiskSimulationService service, IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<RiskSimulationService.SimulationView> create(RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateSimulationRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.executeAuthorized(context, idempotencyKey,
                "risk.simulation.create", request, RiskSimulationService.SimulationView.class,
                () -> service.authorizeCreateReplay(context),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(context,
                        request.candidateType(), request.candidateVersionId(), request.cohortStart(),
                        request.cohortEnd(), request.asOf(), request.labelCutoff(),
                        request.trafficOriginPolicy(), requestId(servletRequest))));
    }

    @GetMapping("/{runId}")
    public RiskSimulationService.SimulationView get(RiskOperatorContext context, @PathVariable UUID runId) {
        return service.get(context, runId);
    }

    @GetMapping("/{runId}/report")
    public RiskSimulationService.SimulationReportView report(
            RiskOperatorContext context, @PathVariable UUID runId) {
        return service.report(context, runId);
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<RiskSimulationService.SimulationView> cancel(RiskOperatorContext context,
            @PathVariable UUID runId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.executeAuthorized(context, idempotencyKey,
                "risk.simulation.cancel:" + runId, request,
                RiskSimulationService.SimulationView.class,
                () -> service.authorizeCancelReplay(context, runId),
                () -> ResponseEntity.ok(service.cancel(context, runId, requestId(servletRequest))));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record CreateSimulationRequest(RiskSimulationCandidateType candidateType,
            UUID candidateVersionId, Instant cohortStart, Instant cohortEnd, Instant asOf,
            Instant labelCutoff, RiskSimulationTrafficOriginPolicy trafficOriginPolicy) { }

    public record EmptyRequest() { }
}
