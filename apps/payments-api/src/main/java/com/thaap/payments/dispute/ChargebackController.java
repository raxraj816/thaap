package com.thaap.payments.dispute;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
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
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/chargebacks")
public class ChargebackController {
    private final ChargebackRepository repository;
    private final ChargebackService service;
    private final IdempotencyService idempotencyService;

    public ChargebackController(ChargebackRepository repository, ChargebackService service,
            IdempotencyService idempotencyService) {
        this.repository = repository;
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<ChargebackResponse> create(MerchantContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateChargebackRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey, "chargebacks.create", request,
                ChargebackResponse.class, () -> service.create(context, request, requestId(servletRequest)));
    }

    @GetMapping("/{id}")
    public ChargebackResponse get(MerchantContext context, @PathVariable UUID id) {
        return repository.findByIdAndMerchantId(id, context.merchantId())
                .map(ChargebackResponse::from)
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.CHARGEBACK_NOT_FOUND, "Chargeback was not found"));
    }

    @PostMapping("/{id}/evidence_packets")
    public ResponseEntity<EvidencePacketResponse> createEvidence(MerchantContext context, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateEvidencePacketRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey, "chargebacks.evidence_packets.create", request,
                EvidencePacketResponse.class,
                () -> service.createEvidence(context, id, request, requestId(servletRequest)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null || value.toString().isBlank() ? "unknown" : value.toString();
    }

    public record CreateChargebackRequest(
            @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String externalEventKey,
            @NotNull UUID paymentIntentId,
            @Min(1) long amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull ChargebackReasonCode reasonCode,
            @NotNull Instant evidenceOccurredAt,
            @NotNull Instant receivedAt,
            @NotNull Instant evidenceDueAt) {
    }

    public record CreateEvidencePacketRequest(boolean deliveryConfirmed,
            boolean customerCommunicationPresent, boolean deviceContinuity) {
    }

    public record ChargebackResponse(UUID id, UUID paymentIntentId, UUID paymentAttemptId,
            UUID authorizationId, UUID fraudDecisionId, long amount, long allocatedAmount, String currency,
            ChargebackReasonCode reasonCode, ChargebackStatus status, String sourceAttestation,
            TrafficOrigin trafficOrigin, Instant evidenceOccurredAt, Instant receivedAt,
            Instant availableAt, Instant evidenceDueAt, Instant createdAt) {
        static ChargebackResponse from(Chargeback chargeback) {
            return new ChargebackResponse(chargeback.getId(), chargeback.getPaymentIntentId(),
                    chargeback.getPaymentAttemptId(), chargeback.getAuthorizationId(),
                    chargeback.getFraudDecisionId(), chargeback.getAmount(), chargeback.getAllocatedAmount(),
                    chargeback.getCurrency(), chargeback.getReasonCode(), chargeback.getStatus(),
                    chargeback.getSourceAttestation(), chargeback.getTrafficOrigin(),
                    chargeback.getEvidenceOccurredAt(), chargeback.getReceivedAt(),
                    chargeback.getAvailableAt(), chargeback.getEvidenceDueAt(), chargeback.getCreatedAt());
        }
    }

    public record EvidencePacketResponse(UUID id, UUID chargebackId, String status, String sourceAttestation,
            DisputeEvidenceResult avsResult, DisputeEvidenceResult cvcResult, boolean threeDsAuthenticated,
            boolean deliveryConfirmed, boolean customerCommunicationPresent, boolean refundHistoryPresent,
            boolean deviceContinuity, Instant dueAt, Instant createdAt) {
        static EvidencePacketResponse from(DisputeEvidencePacket packet) {
            return new EvidencePacketResponse(packet.getId(), packet.getChargebackId(), packet.getStatus(),
                    packet.getSourceAttestation(), packet.getAvsResult(), packet.getCvcResult(),
                    packet.isThreeDsAuthenticated(), packet.isDeliveryConfirmed(),
                    packet.isCustomerCommunicationPresent(), packet.isRefundHistoryPresent(),
                    packet.isDeviceContinuity(), packet.getDueAt(), packet.getCreatedAt());
        }
    }
}
