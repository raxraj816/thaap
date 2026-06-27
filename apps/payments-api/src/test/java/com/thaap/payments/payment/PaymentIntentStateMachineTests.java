package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    @ParameterizedTest
    @EnumSource(value = PaymentIntentStatus.class, names = {
            "CAPTURED", "REFUNDED", "DISPUTED", "FAILED", "CANCELED"
    })
    void cancelRejectsTerminalPaymentStates(PaymentIntentStatus terminalStatus) {
        var intent = paymentIntentIn(terminalStatus);

        assertThatThrownBy(intent::cancel)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PaymentIntent cannot be canceled from status " + terminalStatus);
        assertThat(intent.getStatus()).isEqualTo(terminalStatus);
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
