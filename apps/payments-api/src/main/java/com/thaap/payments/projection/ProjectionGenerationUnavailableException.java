package com.thaap.payments.projection;

/**
 * Signals that a projection has no servable active generation, either because the definition is
 * disabled or because the active generation was built by an incompatible handler version.
 *
 * <p>This is deliberately not an {@link IllegalStateException}. Spring's persistence-exception
 * translation rewrites {@code IllegalStateException} thrown from a {@code @Repository} into
 * {@link org.springframework.dao.InvalidDataAccessApiUsageException}, which silently defeated
 * every caller that tried to degrade a projection-backed section instead of failing the whole
 * read. A dedicated type is not translated, so degradation handlers actually run.
 */
public class ProjectionGenerationUnavailableException extends RuntimeException {

    public ProjectionGenerationUnavailableException(String message) {
        super(message);
    }
}
