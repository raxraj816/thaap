package com.thaap.payments.fraud;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureDefinitionRepository extends JpaRepository<FeatureDefinition, UUID> {

    List<FeatureDefinition> findAllByOrderByFeatureCodeAscFeatureVersionAsc();

    Optional<FeatureDefinition> findByFeatureCodeAndFeatureVersion(String featureCode, String featureVersion);
}
