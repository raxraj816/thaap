package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class PaymentIntentStateMachineTests {

    @Test
    void captureRequiresCaptureReadyState() {
        var intent = paymentIntentIn(PaymentIntentStatus.REQUIRES_CAPTURE);

        intent.capture();

        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
        assertThat(intent.getUpdatedAt()).isAfterOrEqualTo(intent.getCreatedAt());
    }

    @Test
    void captureRejectsPreAuthorizationState() {
        var intent = paymentIntentIn(PaymentIntentStatus.REQUIRES_CONFIRMATION);

        assertThatThrownBy(intent::capture)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PaymentIntent cannot be captured from status REQUIRES_CONFIRMATION");
    }

    @Test
    void cancelAllowsAuthorizedButUncapturedState() {
        var intent = paymentIntentIn(PaymentIntentStatus.REQUIRES_CAPTURE);

        intent.cancel();

        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.CANCELED);
    }

    @Test
    void refundRequiresCapturedState() {
        var intent = paymentIntentIn(PaymentIntentStatus.CAPTURED);

        intent.refund();

        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.REFUNDED);
    }

    @Test
    void refundRequiresFullCapturedAmountUntilPartialRefundsExist() {
        var intent = paymentIntentIn(PaymentIntentStatus.CAPTURED);

        assertThatThrownBy(() -> intent.refund(500))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "Refund amount must equal captured PaymentIntent amount until partial refunds are supported");
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.CAPTURED);
    }

    private PaymentIntent paymentIntentIn(PaymentIntentStatus status) {
        var intent = new PaymentIntent(UUID.randomUUID(), UUID.randomUUID(), 1200, "USD");
        ReflectionTestUtils.setField(intent, "status", status);
        return intent;
    }
}
