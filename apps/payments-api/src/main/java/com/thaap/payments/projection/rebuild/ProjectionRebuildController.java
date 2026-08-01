package com.thaap.payments.projection.rebuild;

import com.thaap.payments.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.InetAddress;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/data/projections/{projectionName}")
public class ProjectionRebuildController {

    private static final String OPERATOR_KEY_HEADER = "X-Platform-Operator-Key";

    private final ProjectionRebuildControlService controlService;

    public ProjectionRebuildController(ProjectionRebuildControlService controlService) {
        this.controlService = controlService;
    }

    @PostMapping("/rebuilds")
    public ResponseEntity<ProjectionRebuildControlService.RebuildView> start(
            @PathVariable String projectionName,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) StartRebuildRequest body,
            HttpServletRequest request) {
        requireLoopback(request);
        String originPolicy = body == null ? "ALL" : body.trafficOriginPolicy();
        ProjectionRebuildControlService.RebuildView result = controlService.start(
                operatorKey, idempotencyKey, projectionName, originPolicy,
                requestId(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(result);
    }

    @GetMapping("/rebuilds/{rebuildId}")
    public ProjectionRebuildControlService.RebuildView status(
            @PathVariable String projectionName, @PathVariable UUID rebuildId,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            HttpServletRequest request) {
        requireLoopback(request);
        return controlService.status(operatorKey, projectionName, rebuildId);
    }

    @PostMapping("/rebuilds/{rebuildId}/cancel")
    public ProjectionRebuildControlService.RebuildView cancel(
            @PathVariable String projectionName, @PathVariable UUID rebuildId,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireLoopback(request);
        return controlService.cancel(operatorKey, idempotencyKey, projectionName,
                rebuildId, requestId(request));
    }

    @PostMapping("/rebuilds/{rebuildId}/reconcile")
    public ResponseEntity<ProjectionRebuildControlService.ReconciliationView> reconcile(
            @PathVariable String projectionName, @PathVariable UUID rebuildId,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireLoopback(request);
        ProjectionRebuildControlService.ReconciliationView result = controlService.reconcile(
                operatorKey, idempotencyKey, projectionName, rebuildId, requestId(request));
        return ResponseEntity.status(result.passed() ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(result);
    }

    @PostMapping("/rebuilds/{rebuildId}/activate")
    public ResponseEntity<ProjectionRebuildControlService.ActivationView> activate(
            @PathVariable String projectionName, @PathVariable UUID rebuildId,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireLoopback(request);
        ProjectionRebuildControlService.ActivationView result = controlService.activate(
                operatorKey, idempotencyKey, projectionName, rebuildId, requestId(request));
        return ResponseEntity.status(result.activated() ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(result);
    }

    @PostMapping("/rollback")
    public ProjectionRebuildControlService.RollbackView rollback(
            @PathVariable String projectionName,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RollbackProjectionRequest body,
            HttpServletRequest request) {
        requireLoopback(request);
        return controlService.rollback(operatorKey, idempotencyKey, projectionName,
                body.targetGenerationId(), requestId(request));
    }

    private void requireLoopback(HttpServletRequest request) {
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        try {
            if (remoteAddress == null
                    || !InetAddress.getByName(remoteAddress).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Projection rebuild routes require a loopback connection");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Projection rebuild routes require a loopback connection");
        }
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record StartRebuildRequest(String trafficOriginPolicy) {
    }

    public record RollbackProjectionRequest(@NotNull UUID targetGenerationId) {
    }
}
