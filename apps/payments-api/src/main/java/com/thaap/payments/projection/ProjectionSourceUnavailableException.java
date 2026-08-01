package com.thaap.payments.projection;

public final class ProjectionSourceUnavailableException extends RuntimeException {

    public ProjectionSourceUnavailableException() {
        super("Canonical source event is unavailable");
    }
}
