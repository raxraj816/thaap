package com.thaap.payments.dispute;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.payment.PaymentIntentStatus;
import com.thaap.payments.payment.PaymentDomainEventService;
import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.Refund;
import com.thaap.payments.payment.RefundRepository;
import com.thaap.payments.review.FraudLabel;
import com.thaap.payments.review.FraudLabelRepository;
import com.thaap.payments.risk.RiskOperationsEventProducer;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.webhook.WebhookDeliveryService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChargebackService {
    private final MerchantRepository merchantRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final AuthorizationRepository authorizationRepository;
    private final RefundRepository refundRepository;
    private final FraudDecisionRepository fraudDecisionRepository;
    private final ChargebackRepository chargebackRepository;
    private final DisputeOffsetAllocationRepository allocationRepository;
    private final DisputeEvidencePacketRepository evidenceRepository;
    private final FraudLabelRepository fraudLabelRepository;
    private final LedgerService ledgerService;
    private final AuditLogService auditLogService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final ChargebackMetrics metrics;
    private final DisputeWorkflowInitializer disputeWorkflowInitializer;
    private final Clock clock;
    private final PaymentDomainEventService paymentDomainEventService;
    private final RiskOperationsEventProducer riskOperationsEventProducer;

    public ChargebackService(MerchantRepository merchantRepository,
            PaymentIntentRepository paymentIntentRepository, AuthorizationRepository authorizationRepository,
            RefundRepository refundRepository, FraudDecisionRepository fraudDecisionRepository,
            ChargebackRepository chargebackRepository, DisputeOffsetAllocationRepository allocationRepository,
            DisputeEvidencePacketRepository evidenceRepository, FraudLabelRepository fraudLabelRepository,
            LedgerService ledgerService, AuditLogService auditLogService,
            WebhookDeliveryService webhookDeliveryService, ChargebackMetrics metrics,
            DisputeWorkflowInitializer disputeWorkflowInitializer, Clock clock,
            PaymentDomainEventService paymentDomainEventService,
            RiskOperationsEventProducer riskOperationsEventProducer) {
        this.merchantRepository = merchantRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.authorizationRepository = authorizationRepository;
        this.refundRepository = refundRepository;
        this.fraudDecisionRepository = fraudDecisionRepository;
        this.chargebackRepository = chargebackRepository;
        this.allocationRepository = allocationRepository;
        this.evidenceRepository = evidenceRepository;
        this.fraudLabelRepository = fraudLabelRepository;
        this.ledgerService = ledgerService;
        this.auditLogService = auditLogService;
        this.webhookDeliveryService = webhookDeliveryService;
        this.metrics = metrics;
        this.disputeWorkflowInitializer = disputeWorkflowInitializer;
        this.clock = clock;
        this.paymentDomainEventService = paymentDomainEventService;
        this.riskOperationsEventProducer = riskOperationsEventProducer;
    }

    public ResponseEntity<ChargebackController.ChargebackResponse> create(MerchantContext context,
            ChargebackController.CreateChargebackRequest request, String requestId) {
        merchantRepository.findByIdForMoneyMutation(context.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.MERCHANT_NOT_FOUND, "Merchant was not found"));
        String fingerprint = fingerprint(request);
        Chargeback duplicate = chargebackRepository
                .findByMerchantIdAndExternalEventKey(context.merchantId(), request.externalEventKey())
                .orElse(null);
        if (duplicate != null) {
            if (!duplicate.getRequestFingerprint().equals(fingerprint)) {
                throw new PublicApiException(PublicApiErrorDescriptor.CHARGEBACK_EVENT_KEY_CONFLICT, "External chargeback event key was already used with different content");
            }
            metrics.recordIngestion(duplicate.getReasonCode(), duplicate.getStatus(), "DEDUPLICATED");
            return ResponseEntity.ok(ChargebackController.ChargebackResponse.from(duplicate));
        }

        PaymentIntent paymentIntent = paymentIntentRepository
                .findByIdAndMerchantIdForUpdate(request.paymentIntentId(), context.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_FOUND, "PaymentIntent was not found"));
        PaymentIntentStatus startingStatus = paymentIntent.getStatus();
        if (startingStatus != PaymentIntentStatus.CAPTURED && startingStatus != PaymentIntentStatus.REFUNDED) {
            throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CHARGEBACKABLE, "PaymentIntent cannot receive a chargeback from status " + startingStatus);
        }
        if (paymentIntent.getCaptureMethod() != CaptureMethod.AUTOMATIC) {
            throw new PublicApiException(PublicApiErrorDescriptor.CHARGEBACK_REQUIRES_AUTOMATIC_CAPTURE, "Chargebacks require an automatically captured PaymentIntent");
        }
        if (!paymentIntent.getCurrency().equals(request.currency().toUpperCase())) {
            throw new PublicApiException(PublicApiErrorDescriptor.CHARGEBACK_CURRENCY_MISMATCH, "Chargeback currency does not match capture");
        }
        if (request.amount() > paymentIntent.getAmount()) {
            throw new PublicApiException(PublicApiErrorDescriptor.CHARGEBACK_AMOUNT_EXCEEDS_CAPTURE, "Chargeback amount exceeds captured PaymentIntent amount");
        }
        requireTimeOrder(request);

        Authorization authorization = authorizationRepository.findApprovedAuthorizationsForPaymentIntent(
                        context.merchantId(), paymentIntent.getId(),
                        com.thaap.payments.paymentattempt.PaymentAttemptStatus.AUTHORIZED,
                        AuthorizationStatus.APPROVED)
                .stream().findFirst()
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_CAPTURE_AUTHORIZATION_EVIDENCE_MISSING, "PaymentIntent does not have approved capture authorization evidence"));
        if (authorization.getAuthorizedAmount() != paymentIntent.getAmount()
                || !authorization.getCurrency().equals(paymentIntent.getCurrency())) {
            throw new PublicApiException(PublicApiErrorDescriptor.CAPTURE_AUTHORIZATION_MISMATCH, "Authorization does not match captured PaymentIntent");
        }
        FraudDecision decision = fraudDecisionRepository
                .findByMerchantIdAndPaymentAttemptId(context.merchantId(), authorization.getPaymentAttemptId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.PAYMENT_ATTEMPT_FRAUD_LINEAGE_MISSING, "PaymentAttempt does not have fraud decision lineage"));

        Refund refund = refundRepository.findByPaymentIntentIdAndMerchantId(
                paymentIntent.getId(), context.merchantId()).orElse(null);
        long priorRefundAmount = refund == null ? 0L : refund.getAmount();
        long allocatedAmount = startingStatus == PaymentIntentStatus.REFUNDED
                ? 0L : Math.min(request.amount(), paymentIntent.getAmount() - priorRefundAmount);
        Instant availableAt = now();
        Chargeback chargeback = chargebackRepository.saveAndFlush(new Chargeback(context.merchantId(),
                request.externalEventKey(), fingerprint, paymentIntent.getId(), authorization.getPaymentAttemptId(),
                authorization.getId(), decision.getId(), request.amount(), allocatedAmount,
                paymentIntent.getCurrency(), request.reasonCode(), paymentIntent.getTrafficOrigin(),
                request.evidenceOccurredAt(), request.receivedAt(), availableAt, request.evidenceDueAt()));
        disputeWorkflowInitializer.initialize(chargeback, availableAt, context, requestId);
        DisputeOffsetAllocation allocation = allocationRepository.saveAndFlush(new DisputeOffsetAllocation(
                chargeback, refund == null ? null : refund.getId(), paymentIntent.getAmount(), priorRefundAmount,
                0L, availableAt));

        LedgerService.ChargebackLedgerResult ledgerResult = null;
        if (allocatedAmount > 0) {
            ledgerResult = ledgerService.recordChargeback(context.merchantId(), paymentIntent.getId(),
                    chargeback.getId(), paymentIntent.getAmount(), allocatedAmount, paymentIntent.getCurrency());
            paymentIntent.dispute();
        }
        FraudLabel label = null;
        if (request.reasonCode().isFraud()) {
            java.util.List<FraudLabel> currentLabels = fraudLabelRepository.findCurrentForPaymentIntent(
                    context.merchantId(), paymentIntent.getId());
            if (currentLabels.size() > 1) {
                throw new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INTENT_FRAUD_LABEL_LINEAGE_CONFLICT, "PaymentIntent has contradictory current fraud-label lineage");
            }
            UUID supersededLabelId = currentLabels.isEmpty() ? null : currentLabels.getFirst().getId();
            label = fraudLabelRepository.saveAndFlush(new FraudLabel(chargeback, supersededLabelId));
            webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                    "fraud_label.created", label.getId(), paymentIntent.getTrafficOrigin());
        }
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "chargeback.created", chargeback.getId(), paymentIntent.getTrafficOrigin());
        auditCreate(context, requestId, startingStatus, paymentIntent, chargeback, allocation, ledgerResult, label);
        paymentDomainEventService.chargebackCreated(paymentIntent, chargeback,
                paymentDomainEventService.merchantActor(context), requestId, null);
        if (label != null) {
            if (label.getSupersedesLabelId() == null) {
                riskOperationsEventProducer.fraudLabelRecorded(context, paymentIntent.getId(),
                        label.getAvailableAt(), requestId, label.getLabelValue().name(), label.getSource(),
                        label.getAvailableAt(), label.getFraudType() == null ? null : label.getFraudType().name(),
                        label.getTrafficOrigin());
            } else {
                riskOperationsEventProducer.fraudLabelSuperseded(context, paymentIntent.getId(),
                        label.getAvailableAt(), requestId, label.getLabelValue().name(), label.getSource(),
                        label.getAvailableAt(), label.getFraudType() == null ? null : label.getFraudType().name(),
                        label.getSupersedesLabelId(), label.getTrafficOrigin());
            }
        }
        metrics.recordIngestion(request.reasonCode(), chargeback.getStatus(), "CREATED");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ChargebackController.ChargebackResponse.from(chargeback));
    }

    public ResponseEntity<ChargebackController.EvidencePacketResponse> createEvidence(MerchantContext context,
            UUID chargebackId, ChargebackController.CreateEvidencePacketRequest request, String requestId) {
        Chargeback chargeback = chargebackRepository.findByIdAndMerchantIdForUpdate(chargebackId,
                        context.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.CHARGEBACK_NOT_FOUND, "Chargeback was not found"));
        DisputeEvidencePacket existing = evidenceRepository
                .findByMerchantIdAndChargebackId(context.merchantId(), chargebackId).orElse(null);
        if (existing != null) {
            throw new PublicApiException(PublicApiErrorDescriptor.DISPUTE_EVIDENCE_PACKET_EXISTS, "Chargeback already has a DisputeEvidencePacket");
        }
        Instant now = now();
        if (now.isAfter(chargeback.getEvidenceDueAt())) {
            metrics.recordEvidence("DEADLINE_EXPIRED");
            throw new PublicApiException(PublicApiErrorDescriptor.DISPUTE_EVIDENCE_DEADLINE_PASSED, "Dispute evidence deadline has passed");
        }
        Authorization authorization = authorizationRepository
                .findByIdAndMerchantId(chargeback.getAuthorizationId(), context.merchantId())
                .orElseThrow(() -> new PublicApiException(PublicApiErrorDescriptor.CHARGEBACK_AUTHORIZATION_EVIDENCE_MISSING, "Chargeback authorization evidence is unavailable"));
        DisputeEvidencePacket packet = evidenceRepository.saveAndFlush(new DisputeEvidencePacket(chargeback,
                mapVerification(authorization.getAvsPostalCodeCheck()),
                mapVerification(authorization.getCvcCheck()),
                authorization.getThreeDsResult() == ThreeDsResult.AUTHENTICATED,
                request.deliveryConfirmed(), request.customerCommunicationPresent(),
                refundRepository.existsByPaymentIntentIdAndMerchantId(chargeback.getPaymentIntentId(),
                        context.merchantId()), request.deviceContinuity(), now));
        webhookDeliveryService.createDeliveriesInCurrentTransaction(context.merchantId(),
                "dispute_evidence_packet.created", packet.getId(), chargeback.getTrafficOrigin());
        auditLogService.recordMerchantApiAction(context, requestId, "dispute_evidence_packet.created",
                "dispute_evidence_packet", packet.getId(),
                "Bounded simulator dispute evidence packet recorded before deadline",
                Map.of("chargebackId", chargebackId.toString(), "status", chargeback.getStatus().name()),
                Map.of("evidencePacketId", packet.getId().toString(), "status", packet.getStatus(),
                        "sourceAttestation", packet.getSourceAttestation()));
        metrics.recordEvidence("CREATED");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ChargebackController.EvidencePacketResponse.from(packet));
    }

    private void auditCreate(MerchantContext context, String requestId, PaymentIntentStatus startingStatus,
            PaymentIntent paymentIntent, Chargeback chargeback, DisputeOffsetAllocation allocation,
            LedgerService.ChargebackLedgerResult ledgerResult, FraudLabel label) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("paymentIntentId", paymentIntent.getId().toString());
        before.put("paymentIntentStatus", startingStatus.name());
        before.put("capturedAmount", paymentIntent.getAmount());
        before.put("currency", paymentIntent.getCurrency());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("chargebackId", chargeback.getId().toString());
        after.put("status", chargeback.getStatus().name());
        after.put("reasonCode", chargeback.getReasonCode().name());
        after.put("allocatedAmount", chargeback.getAllocatedAmount());
        after.put("allocationId", allocation.getId().toString());
        after.put("paymentIntentStatus", paymentIntent.getStatus().name());
        after.put("sourceAttestation", chargeback.getSourceAttestation());
        if (ledgerResult != null) {
            after.put("ledgerTransactionGroupId", ledgerResult.transactionGroupId().toString());
        }
        if (label != null) {
            after.put("fraudLabelId", label.getId().toString());
        }
        auditLogService.recordMerchantApiAction(context, requestId, "chargeback.created", "chargeback",
                chargeback.getId(), "Simulator chargeback and exact liability allocation recorded", before, after);
    }

    private void requireTimeOrder(ChargebackController.CreateChargebackRequest request) {
        if (request.evidenceOccurredAt().isAfter(request.receivedAt())) {
            throw new PublicApiException(PublicApiErrorDescriptor.SIGNAL_EVIDENCE_OCCURRED_AT_INVALID, "evidenceOccurredAt must not be after receivedAt");
        }
        if (request.receivedAt().isAfter(request.evidenceDueAt())) {
            throw new PublicApiException(PublicApiErrorDescriptor.DISPUTE_EVIDENCE_DUE_AT_INVALID, "evidenceDueAt must not be before receivedAt");
        }
        if (request.receivedAt().isAfter(now())) {
            throw new PublicApiException(PublicApiErrorDescriptor.RECEIVED_AT_IN_FUTURE, "receivedAt must not be in the future");
        }
    }

    private String fingerprint(ChargebackController.CreateChargebackRequest request) {
        return SecretHasher.sha256(request.paymentIntentId() + "|" + request.amount() + "|"
                + request.currency().toUpperCase() + "|" + request.reasonCode() + "|"
                + request.evidenceOccurredAt() + "|" + request.receivedAt() + "|" + request.evidenceDueAt());
    }

    private DisputeEvidenceResult mapVerification(VerificationResult result) {
        return switch (result) {
            case PASS -> DisputeEvidenceResult.MATCH;
            case FAIL -> DisputeEvidenceResult.NO_MATCH;
            case NOT_CHECKED -> DisputeEvidenceResult.UNAVAILABLE;
        };
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
