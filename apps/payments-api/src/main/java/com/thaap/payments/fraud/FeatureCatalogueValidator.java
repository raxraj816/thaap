package com.thaap.payments.fraud;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FeatureCatalogueValidator implements ApplicationRunner {

    private final FeatureDefinitionRepository featureDefinitionRepository;

    public FeatureCatalogueValidator(FeatureDefinitionRepository featureDefinitionRepository) {
        this.featureDefinitionRepository = featureDefinitionRepository;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        validate(featureDefinitionRepository.findAllByOrderByFeatureCodeAscFeatureVersionAsc());
    }

    public void validate(List<FeatureDefinition> definitions) {
        Map<String, FeatureDefinition> versionOneDefinitions = new HashMap<>();
        for (FeatureDefinition definition : definitions) {
            if (!FeatureCatalogue.isLexicallyValid(definition.getFeatureCode())) {
                throw new IllegalStateException("Feature catalogue contains an invalid feature code");
            }
            if (!FeatureCatalogue.VERSION.equals(definition.getFeatureVersion())) {
                continue;
            }
            FeatureDefinition duplicate = versionOneDefinitions.put(definition.getFeatureCode(), definition);
            if (duplicate != null) {
                throw new IllegalStateException("Feature catalogue contains a duplicate V1 definition");
            }
        }
        if (!versionOneDefinitions.keySet().equals(FeatureCatalogue.requiredCodes())) {
            throw new IllegalStateException("Feature catalogue does not contain the exact required V1 definitions");
        }
        for (Map.Entry<String, FeatureDefinition> entry : versionOneDefinitions.entrySet()) {
            FeatureDefinition definition = entry.getValue();
            if (definition.getEntityType() != FeatureEntityType.PAYMENT_ATTEMPT
                    || definition.getValueType() != FeatureCatalogue.requiredType(entry.getKey())) {
                throw new IllegalStateException("Feature catalogue definition is incompatible with V1 materialization");
            }
            if (definition.getMaxAgeMs() != null && definition.getMaxAgeMs() <= 0) {
                throw new IllegalStateException("Feature catalogue max age must be positive when present");
            }
        }
    }
}
