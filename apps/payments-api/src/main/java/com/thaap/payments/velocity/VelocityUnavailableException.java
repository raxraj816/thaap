package com.thaap.payments.velocity;

public class VelocityUnavailableException extends RuntimeException {

    private final Cause cause;

    public VelocityUnavailableException(Cause cause, String message) {
        super(message);
        this.cause = cause;
    }

    public Cause getUnavailableCause() {
        return cause;
    }

    public enum Cause {
        WRONG_VERSION,
        INVALID_MEMBER,
        INVALID_LIMIT,
        CARDINALITY_LIMIT,
        VERSION_MISMATCH,
        MALFORMED_RESULT,
        DEPENDENCY_FAILURE
    }
}
