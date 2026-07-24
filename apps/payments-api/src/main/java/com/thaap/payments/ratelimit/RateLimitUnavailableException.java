package com.thaap.payments.ratelimit;

public class RateLimitUnavailableException extends RuntimeException {
    private final Cause unavailableCause;

    public RateLimitUnavailableException(Cause unavailableCause, String message) {
        super(message);
        this.unavailableCause = unavailableCause;
    }

    public Cause getUnavailableCause() {
        return unavailableCause;
    }

    public enum Cause {
        DEPENDENCY_FAILURE,
        MALFORMED_RESULT,
        VERSION_MISMATCH,
        SCRIPT_REJECTED
    }
}
