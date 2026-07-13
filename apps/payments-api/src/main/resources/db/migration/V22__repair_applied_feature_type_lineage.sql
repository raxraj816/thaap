ALTER TABLE feature_values DROP CONSTRAINT fk_feature_values_definition;
ALTER TABLE feature_definitions DROP CONSTRAINT uk_feature_definitions_lineage;

ALTER TABLE feature_definitions ADD CONSTRAINT uk_feature_definitions_lineage
    UNIQUE (id, feature_code, feature_version, value_type);
ALTER TABLE feature_values ADD CONSTRAINT fk_feature_values_definition
    FOREIGN KEY (feature_definition_id, feature_code, feature_version, value_type)
    REFERENCES feature_definitions (id, feature_code, feature_version, value_type);
