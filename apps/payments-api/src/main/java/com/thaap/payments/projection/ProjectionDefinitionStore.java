package com.thaap.payments.projection;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectionDefinitionStore {

    private final JdbcTemplate jdbcTemplate;

    public ProjectionDefinitionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ActiveProjectionGeneration requireActive(String projectionName) {
        return requireActive(projectionName, false);
    }

    /**
     * Locks the definition pointer and selected generation until the caller's transaction ends.
     * Consumers take these locks in payment-then-merchant order so activation and application
     * cannot split one event across a retired and a newly active generation.
     */
    public ActiveProjectionGeneration requireActiveForUpdate(String projectionName) {
        return requireActive(projectionName, true);
    }

    private ActiveProjectionGeneration requireActive(String projectionName, boolean forUpdate) {
        String lockingClause = forUpdate ? " FOR UPDATE" : "";
        List<ActiveProjectionGeneration> matches = jdbcTemplate.query("""
                SELECT definition.id, generation.id, generation.generation_number,
                       generation.handler_version
                FROM projection_definitions definition
                JOIN projection_generations generation
                  ON generation.id = definition.active_generation_id
                 AND generation.projection_definition_id = definition.id
                WHERE definition.projection_name = ?
                  AND definition.lifecycle_status = 'ENABLED'
                  AND generation.lifecycle_status = 'ACTIVE'
                """ + lockingClause, (resultSet, rowNumber) -> new ActiveProjectionGeneration(
                        resultSet.getObject(1, java.util.UUID.class),
                        resultSet.getObject(2, java.util.UUID.class),
                        resultSet.getLong(3), resultSet.getString(4)), projectionName);
        if (matches.size() != 1) {
            throw new ProjectionGenerationUnavailableException(
                    "Projection has no compatible active generation");
        }
        ActiveProjectionGeneration generation = matches.getFirst();
        if (!ProjectionNames.HANDLER_VERSION.equals(generation.handlerVersion())) {
            throw new ProjectionGenerationUnavailableException(
                    "Active projection handler version is incompatible");
        }
        return generation;
    }
}
