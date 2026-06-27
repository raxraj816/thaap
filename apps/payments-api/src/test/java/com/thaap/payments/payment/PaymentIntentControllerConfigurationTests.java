package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.thaap.payments.audit.AuditLogService;
import com.thaap.payments.authorization.AuthorizationRepository;
import com.thaap.payments.authorization.AuthorizationVoidRepository;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.fraud.ConfirmDecisionEngine;
import com.thaap.payments.fraud.FeatureSnapshotRepository;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.ledger.LedgerService;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.processor.ProcessorAdapter;
import com.thaap.payments.webhook.WebhookDeliveryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PaymentIntentControllerConfigurationTests {

    @Test
    void featureSnapshotJsonLimitMustBePositive() {
        assertThatThrownBy(() -> newController(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("thaap.fraud.feature-snapshot.max-json-bytes must be positive");
        assertThatThrownBy(() -> newController(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("thaap.fraud.feature-snapshot.max-json-bytes must be positive");
    }

    @Test
    void featureSnapshotJsonLimitAcceptsPositiveValues() {
        assertThatCode(() -> newController(1)).doesNotThrowAnyException();
    }

    private PaymentIntentController newController(int maxFeatureSnapshotJsonBytes) {
        return new PaymentIntentController(
                mock(PaymentIntentRepository.class),
                mock(CustomerRepository.class),
                mock(PaymentMethodRepository.class),
                mock(PaymentAttemptRepository.class),
                mock(IdempotencyService.class),
                mock(WebhookDeliveryService.class),
                mock(ConfirmDecisionEngine.class),
                mock(ProcessorAdapter.class),
                mock(AuthorizationRepository.class),
                mock(AuthorizationVoidRepository.class),
                mock(FraudDecisionRepository.class),
                mock(FeatureSnapshotRepository.class),
                mock(AuditLogService.class),
                mock(LedgerService.class),
                new SimpleMeterRegistry(),
                maxFeatureSnapshotJsonBytes);
    }
}
