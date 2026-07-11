package com.thaap.payments.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.thaap.payments.fraud.FeatureCatalogueValidator;
import com.thaap.payments.fraud.FeatureDefinitionRepository;
import com.thaap.payments.fraud.FeatureMaterializationMetrics;
import com.thaap.payments.fraud.FeatureMaterializer;
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

    private FeatureMaterializer newController(int maxFeatureSnapshotJsonBytes) {
        FeatureDefinitionRepository repository = mock(FeatureDefinitionRepository.class);
        FeatureCatalogueValidator validator = new FeatureCatalogueValidator(repository);
        return new FeatureMaterializer(repository, validator,
                new FeatureMaterializationMetrics(new SimpleMeterRegistry()), maxFeatureSnapshotJsonBytes);
    }
}
