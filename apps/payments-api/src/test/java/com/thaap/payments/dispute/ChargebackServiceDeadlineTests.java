package com.thaap.payments.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authorization.Authorization;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationStatus;
import com.thaap.payments.authorization.NetworkStatus;
import com.thaap.payments.authorization.ThreeDsResult;
import com.thaap.payments.authorization.VerificationResult;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.payment.RefundRepository;
import com.thaap.payments.review.FraudLabelRepository;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import com.thaap.payments.webhook.WebhookDeliveryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class ChargebackServiceDeadlineTests {
    private static final Instant DUE_AT = Instant.parse("2026-07-14T00:00:00Z");

    @Test
    void exactDueInstantIsAcceptedAndOneMicrosecondAfterIsRejected() {
        Fixture exact = fixture(Clock.fixed(DUE_AT, ZoneOffset.UTC));
        ResponseEntity<ChargebackController.EvidencePacketResponse> response = exact.service().createEvidence(
                exact.context(), exact.chargeback().getId(),
                new ChargebackController.CreateEvidencePacketRequest(true, false, true), "deadline-at");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().createdAt()).isEqualTo(DUE_AT);
        verify(exact.evidenceRepository()).saveAndFlush(any(DisputeEvidencePacket.class));

        Fixture after = fixture(Clock.fixed(DUE_AT.plusNanos(1_000), ZoneOffset.UTC));
        assertThatThrownBy(() -> after.service().createEvidence(after.context(), after.chargeback().getId(),
                new ChargebackController.CreateEvidencePacketRequest(true, false, true), "deadline-after"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Dispute evidence deadline has passed");
    }

    private Fixture fixture(Clock clock) {
        UUID merchantId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID paymentAttemptId = UUID.randomUUID();
        UUID fraudDecisionId = UUID.randomUUID();
        Authorization authorization = new Authorization(merchantId, paymentAttemptId, "SIMULATOR",
                "auth-deadline-" + UUID.randomUUID(), "APPROVED", NetworkStatus.APPROVED,
                1000L, 1000L, "USD", AuthorizationStatus.APPROVED, VerificationResult.PASS,
                VerificationResult.PASS, VerificationResult.FAIL, ThreeDsResult.AUTHENTICATED, "APPROVED");
        Chargeback chargeback = new Chargeback(merchantId, "deadline.event", "a".repeat(64),
                paymentIntentId, paymentAttemptId, authorization.getId(), fraudDecisionId,
                1000L, 1000L, "USD", ChargebackReasonCode.GENERAL, TrafficOrigin.MERCHANT_API,
                DUE_AT.minusSeconds(7200), DUE_AT.minusSeconds(3600), DUE_AT.minusSeconds(1800), DUE_AT);

        ChargebackRepository chargebackRepository = mock(ChargebackRepository.class);
        DisputeEvidencePacketRepository evidenceRepository = mock(DisputeEvidencePacketRepository.class);
        AuthorizationRepository authorizationRepository = mock(AuthorizationRepository.class);
        RefundRepository refundRepository = mock(RefundRepository.class);
        when(chargebackRepository.findByIdAndMerchantIdForUpdate(chargeback.getId(), merchantId))
                .thenReturn(Optional.of(chargeback));
        when(evidenceRepository.findByMerchantIdAndChargebackId(merchantId, chargeback.getId()))
                .thenReturn(Optional.empty());
        when(authorizationRepository.findByIdAndMerchantId(authorization.getId(), merchantId))
                .thenReturn(Optional.of(authorization));
        when(evidenceRepository.saveAndFlush(any(DisputeEvidencePacket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChargebackService service = new ChargebackService(mock(MerchantRepository.class),
                mock(PaymentIntentRepository.class), authorizationRepository, refundRepository,
                mock(FraudDecisionRepository.class), chargebackRepository,
                mock(DisputeOffsetAllocationRepository.class), evidenceRepository,
                mock(FraudLabelRepository.class), mock(LedgerService.class), mock(AuditLogService.class),
                mock(WebhookDeliveryService.class), mock(ChargebackMetrics.class), clock);
        return new Fixture(service, new MerchantContext(merchantId, "deadline", "sk_test"),
                chargeback, evidenceRepository);
    }

    private record Fixture(ChargebackService service, MerchantContext context, Chargeback chargeback,
            DisputeEvidencePacketRepository evidenceRepository) {
    }
}
