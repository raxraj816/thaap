package com.thaap.payments.projection.rebuild;

public class ProjectionRebuildFenceException extends IllegalStateException {

    public ProjectionRebuildFenceException() {
        super("Projection rebuild worker lost its lease fence");
    }
}
