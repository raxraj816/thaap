package com.thaap.payments.fraud;

public class FeatureMaterializationException extends RuntimeException {

    private final String causeCode;
    private final String featureCode;

    public FeatureMaterializationException(String causeCode, String message) {
        this(causeCode, "NONE", message, null);
    }

    public FeatureMaterializationException(String causeCode, String featureCode, String message) {
        this(causeCode, featureCode, message, null);
    }

    public FeatureMaterializationException(String causeCode, String message, Throwable cause) {
        this(causeCode, "NONE", message, cause);
    }

    private FeatureMaterializationException(String causeCode, String featureCode, String message, Throwable cause) {
        super(message, cause);
        this.causeCode = causeCode;
        this.featureCode = featureCode;
    }

    public String causeCode() {
        return causeCode;
    }

    public String featureCode() {
        return featureCode;
    }
}
