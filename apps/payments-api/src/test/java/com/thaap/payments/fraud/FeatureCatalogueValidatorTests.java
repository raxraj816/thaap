package com.thaap.payments.fraud;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FeatureCatalogueValidatorTests {

    private final FeatureCatalogueValidator validator =
            new FeatureCatalogueValidator(mock(FeatureDefinitionRepository.class));

    @Test
    void exactV1CatalogueIsAccepted() {
        assertThatCode(() -> validator.validate(validDefinitions())).doesNotThrowAnyException();
    }

    @Test
    void missingDuplicateWrongTypeAndMalformedPartitionsFailClosed() {
        List<FeatureDefinition> missing = validDefinitions();
        missing.removeFirst();
        assertThatThrownBy(() -> validator.validate(missing)).isInstanceOf(IllegalStateException.class);

        List<FeatureDefinition> duplicate = validDefinitions();
        duplicate.add(definition("card.country", FeatureValueType.STRING));
        assertThatThrownBy(() -> validator.validate(duplicate)).isInstanceOf(IllegalStateException.class);

        List<FeatureDefinition> wrongType = validDefinitions();
        wrongType.set(0, definition("card.country", FeatureValueType.LONG));
        assertThatThrownBy(() -> validator.validate(wrongType)).isInstanceOf(IllegalStateException.class);

        List<FeatureDefinition> malformed = validDefinitions();
        malformed.add(definition("UPPER CASE", FeatureValueType.STRING));
        ReflectionTestUtils.setField(malformed.getLast(), "featureVersion", "v2");
        assertThatThrownBy(() -> validator.validate(malformed)).isInstanceOf(IllegalStateException.class);

        List<FeatureDefinition> invalidAge = validDefinitions();
        ReflectionTestUtils.setField(invalidAge.getFirst(), "maxAgeMs", 0L);
        assertThatThrownBy(() -> validator.validate(invalidAge)).isInstanceOf(IllegalStateException.class);
    }

    private List<FeatureDefinition> validDefinitions() {
        List<FeatureDefinition> definitions = new ArrayList<>();
        for (String code : FeatureCatalogue.requiredCodes()) {
            definitions.add(definition(code, FeatureCatalogue.requiredType(code)));
        }
        return definitions;
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
}
