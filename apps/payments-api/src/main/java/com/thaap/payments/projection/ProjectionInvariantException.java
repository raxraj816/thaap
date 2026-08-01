package com.thaap.payments.projection;

public final class ProjectionInvariantException extends RuntimeException {

    public ProjectionInvariantException(String safeMessage) {
        super(safeMessage);
    }
}
