package com.thaap.payments.incident;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/risk/incidents")
public class CardTestingIncidentController {
    private final CardTestingIncidentRepository incidentRepository;
    private final CardTestingIncidentResolutionRepository resolutionRepository;
    private final CardTestingIncidentService service;
    private final IdempotencyService idempotencyService;

    public CardTestingIncidentController(CardTestingIncidentRepository incidentRepository,
            CardTestingIncidentResolutionRepository resolutionRepository,
            CardTestingIncidentService service, IdempotencyService idempotencyService) {
        this.incidentRepository = incidentRepository;
        this.resolutionRepository = resolutionRepository;
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public List<IncidentResponse> list(RiskOperatorContext context,
            @RequestParam(defaultValue = "OPEN") CardTestingIncidentStatus status,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(name = "starting_after", required = false) UUID startingAfter) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        List<CardTestingIncident> incidents;
        if (startingAfter == null) {
            incidents = incidentRepository.list(context.merchantId(), status, PageRequest.of(0, limit));
        } else {
            CardTestingIncident cursor = incidentRepository.findByIdAndMerchantId(
                            startingAfter, context.merchantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "CardTestingIncident cursor was not found"));
            incidents = incidentRepository.listAfter(context.merchantId(), status,
                    cursor.getCreatedAt(), cursor.getId(), PageRequest.of(0, limit));
        }
        return incidents.stream().map(incident -> response(context.merchantId(), incident)).toList();
    }

    @GetMapping("/{id}")
    public IncidentResponse detail(RiskOperatorContext context, @PathVariable UUID id) {
        CardTestingIncident incident = incidentRepository.findByIdAndMerchantId(id, context.merchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "CardTestingIncident was not found"));
        return response(context.merchantId(), incident);
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<IncidentResponse> resolve(RiskOperatorContext context, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResolveIncidentRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.incidents.resolve:" + id, request, IncidentResponse.class,
                () -> ResponseEntity.ok(IncidentResponse.from(service.resolve(context, id,
                        request.mitigationCode(), requestId(servletRequest)))));
    }

    private IncidentResponse response(UUID merchantId, CardTestingIncident incident) {
        CardTestingIncidentResolution resolution = resolutionRepository
                .findByMerchantIdAndIncidentId(merchantId, incident.getId()).orElse(null);
        return IncidentResponse.from(incident, resolution);
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null || value.toString().isBlank() ? "unknown" : value.toString();
    }

    public record ResolveIncidentRequest(@NotNull IncidentMitigationCode mitigationCode) {}

    public record IncidentResponse(UUID id, CardTestingIncidentStatus status, String triggerCode,
            String sourceAttestation, Instant detectionWindowStartedAt, Instant detectionWindowEndedAt,
            int attemptCount, int declineCount, int distinctCardCount, int distinctBinCount,
            int distinctIpCount, Instant firstEvidenceAt, Instant lastEvidenceAt,
            IncidentMitigationCode mitigationCode, Instant resolvedAt, Instant createdAt, Instant updatedAt) {
        static IncidentResponse from(CardTestingIncidentService.ResolutionResult result) {
            return from(result.incident(), result.resolution());
        }

        static IncidentResponse from(CardTestingIncident incident,
                CardTestingIncidentResolution resolution) {
            return new IncidentResponse(incident.getId(), incident.getStatus(), incident.getTriggerCode(),
                    incident.getSourceAttestation(), incident.getDetectionWindowStartedAt(),
                    incident.getDetectionWindowEndedAt(), incident.getAttemptCount(), incident.getDeclineCount(),
                    incident.getDistinctCardCount(), incident.getDistinctBinCount(), incident.getDistinctIpCount(),
                    incident.getFirstEvidenceAt(), incident.getLastEvidenceAt(),
                    resolution == null ? null : resolution.getMitigationCode(), incident.getResolvedAt(),
                    incident.getCreatedAt(), incident.getUpdatedAt());
        }
    }
}
