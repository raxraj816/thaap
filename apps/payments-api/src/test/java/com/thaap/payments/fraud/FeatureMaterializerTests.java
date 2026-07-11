package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.payment.CaptureMethod;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.CardFunding;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.tenant.TrafficOrigin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FeatureMaterializerTests {

    @Test
    void featureCatalogueRejectsEveryProhibitedSourceAndProducesLexicalSnapshot() {
        Fixture fixture = fixture(null);
        Instant decisionStart = Instant.now();

        MaterializedFeatureSet materialized = fixture.materializer().materialize(
                fixture.paymentIntent(), fixture.paymentAttempt(), fixture.paymentMethod(), decisionStart);

        assertThat(materialized.snapshot().keySet()).containsExactly(
                "card.country", "card.funding", "payment.amount_minor", "payment.attempt_number",
                "payment.capture_method", "payment.currency", "traffic.origin");
        assertThat(materialized.snapshot()).containsAllEntriesOf(Map.of(
                "card.country", "US",
                "card.funding", "CREDIT",
                "payment.amount_minor", 1200L,
                "payment.attempt_number", 1L,
                "payment.capture_method", "MANUAL",
                "payment.currency", "USD",
                "traffic.origin", "MERCHANT_API"));
        assertThat(materialized.decisionFeatureContext().features()).isSameAs(materialized.snapshot());
        assertThat(materialized.snapshot().toString()).doesNotContain(
                "424242", "card-last4", "fingerprint-secret", "token-secret",
                fixture.paymentIntent().getId().toString(), fixture.paymentMethod().getId().toString());
        assertThat(materialized.values()).hasSize(7);
        assertThat(materialized.freshnessStatus()).isEqualTo(FeatureFreshnessStatus.FRESH);
        assertThat(materialized.staleFeatureNames()).isEmpty();
        assertThat(materialized.missingFeatureNames()).isEmpty();

        assertThatThrownBy(() -> new MaterializedFeatureSet(materialized.decisionFeatureContext(),
                materialized.values().subList(0, 6), materialized.computedAt(),
                materialized.oldestFeatureTimestamp(), materialized.maxFeatureAgeMs(),
                materialized.freshnessStatus(), materialized.staleFeatureNames(),
                materialized.missingFeatureNames())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agree exactly");

        Map<String, Object> divergentSnapshot = new java.util.LinkedHashMap<>(materialized.snapshot());
        divergentSnapshot.put("payment.amount_minor", 9999L);
        assertThatThrownBy(() -> new MaterializedFeatureSet(new DecisionFeatureContext(divergentSnapshot),
                materialized.values(), materialized.computedAt(), materialized.oldestFeatureTimestamp(),
                materialized.maxFeatureAgeMs(), materialized.freshnessStatus(),
                materialized.staleFeatureNames(), materialized.missingFeatureNames()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agree exactly");
    }

    @Test
    void staleAndFutureTimestampPartitionsAreDistinct() {
        Fixture staleFixture = fixture(1L);
        Instant old = Instant.now().minusSeconds(10);
        ReflectionTestUtils.setField(staleFixture.paymentIntent(), "createdAt", old);
        ReflectionTestUtils.setField(staleFixture.paymentMethod(), "createdAt", old);
        ReflectionTestUtils.setField(staleFixture.paymentAttempt(), "createdAt", old);

        MaterializedFeatureSet stale = staleFixture.materializer().materialize(staleFixture.paymentIntent(),
                staleFixture.paymentAttempt(), staleFixture.paymentMethod(), Instant.now());
        assertThat(stale.freshnessStatus()).isEqualTo(FeatureFreshnessStatus.STALE);
        assertThat(stale.staleFeatureNames()).containsExactly("card.country");
        assertThat(stale.maxFeatureAgeMs()).isGreaterThanOrEqualTo(9_000L);

        Fixture futureFixture = fixture(null);
        assertThatThrownBy(() -> futureFixture.materializer().materialize(futureFixture.paymentIntent(),
                futureFixture.paymentAttempt(), futureFixture.paymentMethod(), Instant.EPOCH))
                .isInstanceOf(FeatureMaterializationException.class)
                .hasMessageContaining("after decision start");
    }

    private Fixture fixture(Long cardCountryMaxAgeMs) {
        FeatureDefinitionRepository repository = mock(FeatureDefinitionRepository.class);
        List<FeatureDefinition> definitions = new ArrayList<>();
        for (String code : FeatureCatalogue.requiredCodes()) {
            FeatureDefinition definition = definition(code, FeatureCatalogue.requiredType(code));
            if ("card.country".equals(code)) {
                ReflectionTestUtils.setField(definition, "maxAgeMs", cardCountryMaxAgeMs);
            }
            definitions.add(definition);
        }
        when(repository.findAllByOrderByFeatureCodeAscFeatureVersionAsc()).thenReturn(definitions);
        FeatureCatalogueValidator validator = new FeatureCatalogueValidator(repository);
        FeatureMaterializer materializer = new FeatureMaterializer(repository, validator,
                new FeatureMaterializationMetrics(new SimpleMeterRegistry()), 16_384);

        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PaymentIntent paymentIntent = new PaymentIntent(merchantId, customerId, 1200L, "USD",
                CaptureMethod.MANUAL, "unsafe description", Map.of("unsafe", "metadata"),
                TrafficOrigin.MERCHANT_API);
        PaymentMethod paymentMethod = new PaymentMethod(merchantId, customerId, "token-secret",
                "fingerprint-secret", "424242", "4242", CardFunding.CREDIT, "US");
        PaymentAttempt paymentAttempt = new PaymentAttempt(merchantId, paymentIntent.getId(), paymentMethod.getId(),
                "unsafe-idempotency-key", 1, TrafficOrigin.MERCHANT_API);
        return new Fixture(materializer, paymentIntent, paymentAttempt, paymentMethod);
    }

    private FeatureDefinition definition(String code, FeatureValueType type) {
        FeatureDefinition definition = new FeatureDefinition();
        ReflectionTestUtils.setField(definition, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(definition, "featureCode", code);
        ReflectionTestUtils.setField(definition, "featureVersion", FeatureCatalogue.VERSION);
        ReflectionTestUtils.setField(definition, "entityType", FeatureEntityType.PAYMENT_ATTEMPT);
        ReflectionTestUtils.setField(definition, "valueType", type);
        return definition;
    }

    private record Fixture(FeatureMaterializer materializer, PaymentIntent paymentIntent,
            PaymentAttempt paymentAttempt, PaymentMethod paymentMethod) {
    }
}
