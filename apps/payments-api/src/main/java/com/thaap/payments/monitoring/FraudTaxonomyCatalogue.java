package com.thaap.payments.monitoring;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class FraudTaxonomyCatalogue {

    public static final String VERSION = "fraud_taxonomy_v1";

    private static final List<Definition> DEFINITIONS = List.of(
            definition(FraudTaxonomySourceType.FRAUD_TYPE, "STOLEN_PAYMENT_CREDENTIAL",
                    "PAYMENT_CREDENTIAL_ABUSE", "STOLEN_PAYMENT_CREDENTIAL",
                    "Stolen payment credential"),
            definition(FraudTaxonomySourceType.FRAUD_TYPE, "ACCOUNT_TAKEOVER",
                    "ACCOUNT_COMPROMISE", "ACCOUNT_TAKEOVER", "Account takeover"),
            definition(FraudTaxonomySourceType.FRAUD_TYPE, "CARD_TESTING",
                    "PAYMENT_CREDENTIAL_ABUSE", "CARD_TESTING", "Card testing"),
            definition(FraudTaxonomySourceType.FRAUD_TYPE, "FIRST_PARTY_MISUSE",
                    "FIRST_PARTY_MISUSE", "FIRST_PARTY_MISUSE", "First-party misuse"),
            definition(FraudTaxonomySourceType.FRAUD_TYPE, "OTHER_CONFIRMED_FRAUD",
                    "OTHER", "OTHER_CONFIRMED_FRAUD", "Other confirmed fraud"),
            definition(FraudTaxonomySourceType.CHARGEBACK_REASON,
                    "FRAUDULENT_STOLEN_CREDENTIAL", "PAYMENT_CREDENTIAL_ABUSE",
                    "STOLEN_PAYMENT_CREDENTIAL", "Fraudulent stolen credential"),
            definition(FraudTaxonomySourceType.CHARGEBACK_REASON,
                    "FRAUDULENT_ACCOUNT_TAKEOVER", "ACCOUNT_COMPROMISE",
                    "ACCOUNT_TAKEOVER", "Fraudulent account takeover"),
            definition(FraudTaxonomySourceType.CHARGEBACK_REASON,
                    "FRAUDULENT_CARD_TESTING", "PAYMENT_CREDENTIAL_ABUSE",
                    "CARD_TESTING", "Fraudulent card testing"),
            definition(FraudTaxonomySourceType.CHARGEBACK_REASON,
                    "FRAUDULENT_FIRST_PARTY_MISUSE", "FIRST_PARTY_MISUSE",
                    "FIRST_PARTY_MISUSE", "Fraudulent first-party misuse"),
            definition(FraudTaxonomySourceType.CHARGEBACK_REASON, "PRODUCT_NOT_RECEIVED",
                    "NON_FRAUD_DISPUTE", null, "Product not received"),
            definition(FraudTaxonomySourceType.CHARGEBACK_REASON, "DUPLICATE",
                    "NON_FRAUD_DISPUTE", null, "Duplicate charge"),
            definition(FraudTaxonomySourceType.CHARGEBACK_REASON, "CREDIT_NOT_PROCESSED",
                    "NON_FRAUD_DISPUTE", null, "Credit not processed"),
            definition(FraudTaxonomySourceType.CHARGEBACK_REASON, "GENERAL",
                    "NON_FRAUD_DISPUTE", null, "General dispute"),
            definition(FraudTaxonomySourceType.INCIDENT_TRIGGER,
                    "PERSISTED_CARD_TESTING_PATTERN", "PAYMENT_CREDENTIAL_ABUSE",
                    "CARD_TESTING", "Persisted card-testing pattern"),
            definition(FraudTaxonomySourceType.SCENARIO_LABEL,
                    "happy_path_approved_authorization", "LEGITIMATE_OR_UNKNOWN", null,
                    "Happy-path approved authorization"),
            definition(FraudTaxonomySourceType.SCENARIO_LABEL,
                    "retryable_processor_decline", "LEGITIMATE_OR_UNKNOWN", null,
                    "Retryable processor decline"),
            definition(FraudTaxonomySourceType.SCENARIO_LABEL,
                    "basic_card_testing_pattern", "PAYMENT_CREDENTIAL_ABUSE", "CARD_TESTING",
                    "Basic card-testing pattern"),
            definition(FraudTaxonomySourceType.REPORTING_BUCKET, "UNCLASSIFIED",
                    "LEGITIMATE_OR_UNKNOWN", null, "Unclassified"));

    private FraudTaxonomyCatalogue() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Map<Key, Definition> definitionsByKey() {
        return DEFINITIONS.stream().collect(Collectors.toUnmodifiableMap(
                Definition::key, Function.identity()));
    }

    private static Definition definition(FraudTaxonomySourceType sourceType, String sourceCode,
            String fraudFamily, String fraudType, String displayLabel) {
        return new Definition(new Key(sourceType, sourceCode), fraudFamily, fraudType, displayLabel);
    }

    public record Key(FraudTaxonomySourceType sourceType, String sourceCode) { }

    public record Definition(Key key, String fraudFamily, String fraudType, String displayLabel) { }
}
