package com.thaap.payments.replay;

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
@RequestMapping("/risk/replay-datasets")
public class OfflineReplayDatasetController {

    private final OfflineReplayDatasetService service;
    private final IdempotencyService idempotencyService;

    public OfflineReplayDatasetController(OfflineReplayDatasetService service,
            IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<OfflineReplayDatasetService.ReplayDatasetView> create(
            RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateReplayDatasetRequest request,
            HttpServletRequest servletRequest) {
        return idempotencyService.executeAuthorized(context, idempotencyKey,
                "risk.replay-dataset.create", request,
                OfflineReplayDatasetService.ReplayDatasetView.class,
                () -> service.authorizeCreateReplay(context),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(context,
                        request.eventCutoffManifestId(), request.projectionGenerationId(),
                        request.compatibilitySimulationRunId(), request.cohortStart(),
                        request.cohortEnd(), request.labelAvailabilityCutoff(),
                        request.trafficOriginPolicy(), requestId(servletRequest))));
    }

    @GetMapping("/{datasetId}")
    public OfflineReplayDatasetService.ReplayDatasetView get(
            RiskOperatorContext context, @PathVariable UUID datasetId) {
        return service.get(context, datasetId);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    public record CreateReplayDatasetRequest(UUID eventCutoffManifestId,
            UUID projectionGenerationId, UUID compatibilitySimulationRunId,
            Instant cohortStart, Instant cohortEnd, Instant labelAvailabilityCutoff,
            ReplayDatasetTrafficOriginPolicy trafficOriginPolicy) { }
}
