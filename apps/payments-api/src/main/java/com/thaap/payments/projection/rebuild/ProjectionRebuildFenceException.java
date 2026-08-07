package com.thaap.payments.projection.rebuild;

/**
 * Signals that a rebuild worker or operator acted on a lease it no longer owns.
 *
 * <p>Extends {@link RuntimeException} rather than {@link IllegalStateException}, and the
 * distinction is load-bearing. This is thrown from {@link ProjectionRebuildStore}, which is a
 * {@code @Repository}, so every throw from there passes through Spring's
 * {@code PersistenceExceptionTranslationInterceptor}. The Hibernate translator maps
 * {@code IllegalStateException} to {@code InvalidDataAccessApiUsageException}, because the JPA
 * specification treats it as API misuse — so as an {@code IllegalStateException} this type was
 * rewrapped on the way out of the store and a caller writing
 * {@code catch (ProjectionRebuildFenceException)} would never have caught it. Worse, it was
 * rewrapped inconsistently: the same type surfaced raw from {@link ProjectionRebuildApplier},
 * {@link ProjectionRebuildControlService} and {@link ProjectionReconciliationEngine}, which are
 * not repositories. A plain {@code RuntimeException} is not in that mapping and survives
 * translation unchanged from all four.
 *
 * <p>It is deliberately not a {@code DataAccessException} either. Losing a fence is a domain
 * ownership decision, not a database access failure, and Spring's data-access hierarchy splits
 * on transient versus non-transient — a retry classification that would be actively wrong here,
 * since the fenced loser must stop rather than try again.
 *
 * <p>Mirrors {@code OfflineReplayDatasetWorker.StaleReplayDatasetWorkerException}, which extends
 * {@code RuntimeException} for the same reason.
 */
public final class ProjectionRebuildFenceException extends RuntimeException {

    public ProjectionRebuildFenceException() {
        super("Projection rebuild worker lost its lease fence");
    }
}
