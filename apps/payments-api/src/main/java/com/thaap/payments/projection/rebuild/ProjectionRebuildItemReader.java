package com.thaap.payments.projection.rebuild;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.jdbc.core.JdbcTemplate;

public class ProjectionRebuildItemReader implements ItemStreamReader<ProjectionRebuildInput> {

    private static final int PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final UUID rebuildId;
    private final Deque<ProjectionRebuildInput> buffer = new ArrayDeque<>();
    private UUID cutoffManifestId;
    private InputPhase phase = InputPhase.BOOTSTRAP;
    private UUID lastMerchantId;
    private String lastStreamType;
    private UUID lastStreamId;
    private long lastStreamVersion;
    private boolean exhausted;

    public ProjectionRebuildItemReader(JdbcTemplate jdbcTemplate, UUID rebuildId) {
        this.jdbcTemplate = jdbcTemplate;
        this.rebuildId = rebuildId;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        RebuildCursor cursor = jdbcTemplate.query("""
                SELECT cutoff_manifest_id, last_checkpoint_key
                FROM projection_rebuilds WHERE id = ?
                """, resultSet -> resultSet.next()
                        ? new RebuildCursor(resultSet.getObject(1, UUID.class),
                                resultSet.getString(2)) : null,
                rebuildId);
        if (cursor == null) {
            throw new IllegalStateException("Projection rebuild input boundary is unavailable");
        }
        cutoffManifestId = cursor.cutoffManifestId();
        if (cursor.lastCheckpointKey() != null) {
            restore(cursor.lastCheckpointKey());
        }
    }

    @Override
    public ProjectionRebuildInput read() {
        while (buffer.isEmpty() && !exhausted) {
            loadPage();
        }
        ProjectionRebuildInput input = buffer.pollFirst();
        if (input != null) {
            lastMerchantId = input.merchantId();
            lastStreamType = input.streamType();
            lastStreamId = input.streamId();
            lastStreamVersion = input.streamVersion();
        }
        return input;
    }

    private void loadPage() {
        if (phase == InputPhase.BOOTSTRAP) {
            List<ProjectionRebuildInput> page = loadBootstrapPage();
            if (!page.isEmpty()) {
                buffer.addAll(page);
                return;
            }
            phase = InputPhase.EVENT;
            lastMerchantId = null;
            lastStreamType = null;
            lastStreamId = null;
            lastStreamVersion = 0;
        }
        List<ProjectionRebuildInput> page = loadEventPage();
        buffer.addAll(page);
        exhausted = page.isEmpty();
    }

    private List<ProjectionRebuildInput> loadBootstrapPage() {
        String keyset = lastMerchantId == null ? "" : """
                 AND (fact.merchant_id > ?
                   OR (fact.merchant_id = ? AND fact.source_id > ?))
                """;
        String sql = """
                SELECT fact.id, fact.merchant_id, fact.source_id,
                       fact.base_stream_version, fact.canonical_fact_bytes,
                       fact.canonical_fact_sha256, fact.source_cutoff_at
                FROM projection_cutoff_manifests cutoff
                JOIN projection_bootstrap_facts fact
                  ON fact.bootstrap_manifest_id = cutoff.bootstrap_manifest_id
                WHERE cutoff.id = ? AND fact.source_type = 'PAYMENT_INTENT'
                """ + keyset + """
                ORDER BY fact.merchant_id, fact.source_id
                LIMIT ?
                """;
        Object[] parameters = lastMerchantId == null
                ? new Object[] {cutoffManifestId, PAGE_SIZE}
                : new Object[] {cutoffManifestId, lastMerchantId, lastMerchantId,
                        lastStreamId, PAGE_SIZE};
        return jdbcTemplate.query(sql, (resultSet, rowNumber) ->
                ProjectionRebuildInput.bootstrap(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class),
                        resultSet.getObject(3, UUID.class), resultSet.getLong(4),
                        resultSet.getBytes(5), resultSet.getString(6),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 7)), parameters);
    }

    private List<ProjectionRebuildInput> loadEventPage() {
        String keyset = lastMerchantId == null ? "" : """
                 AND (event.merchant_id > ?
                   OR (event.merchant_id = ? AND event.stream_type > ?)
                   OR (event.merchant_id = ? AND event.stream_type = ?
                       AND event.stream_id > ?)
                   OR (event.merchant_id = ? AND event.stream_type = ?
                       AND event.stream_id = ? AND event.stream_version > ?))
                """;
        String sql = """
                SELECT event.id, event.merchant_id, event.stream_type, event.stream_id,
                       event.stream_version, event.event_record_bytes,
                       event.event_record_sha256, event.recorded_at
                FROM projection_cutoff_stream_positions cutoff
                JOIN projection_cutoff_manifests manifest ON manifest.id = cutoff.cutoff_manifest_id
                JOIN domain_events event
                  ON event.merchant_id = cutoff.merchant_id
                 AND event.stream_type = cutoff.stream_type
                 AND event.stream_id = cutoff.stream_id
                 AND event.stream_version <= cutoff.maximum_stream_version
                LEFT JOIN projection_bootstrap_facts fact
                  ON fact.bootstrap_manifest_id = manifest.bootstrap_manifest_id
                 AND fact.merchant_id = event.merchant_id
                 AND fact.source_type = 'PAYMENT_INTENT'
                 AND fact.source_id = event.stream_id
                 AND event.stream_type = 'PAYMENT'
                WHERE cutoff.cutoff_manifest_id = ?
                  AND event.stream_version > COALESCE(fact.base_stream_version, 0)
                """ + keyset + """
                ORDER BY event.merchant_id, event.stream_type, event.stream_id,
                         event.stream_version
                LIMIT ?
                """;
        Object[] parameters;
        if (lastMerchantId == null) {
            parameters = new Object[] {cutoffManifestId, PAGE_SIZE};
        } else {
            parameters = new Object[] {cutoffManifestId,
                    lastMerchantId, lastMerchantId, lastStreamType,
                    lastMerchantId, lastStreamType, lastStreamId,
                    lastMerchantId, lastStreamType, lastStreamId, lastStreamVersion,
                    PAGE_SIZE};
        }
        return jdbcTemplate.query(sql, (resultSet, rowNumber) ->
                ProjectionRebuildInput.event(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getString(3),
                        resultSet.getObject(4, UUID.class), resultSet.getLong(5),
                        resultSet.getBytes(6), resultSet.getString(7),
                        com.thaap.payments.persistence.JdbcTime.instant(resultSet, 8)), parameters);
    }

    private void restore(String checkpointKey) {
        String[] fields = checkpointKey.split("\\|", -1);
        try {
            if (fields.length == 3 && "B".equals(fields[0])) {
                phase = InputPhase.BOOTSTRAP;
                lastMerchantId = UUID.fromString(fields[1]);
                lastStreamId = UUID.fromString(fields[2]);
                return;
            }
            if (fields.length == 6 && "E".equals(fields[0])) {
                phase = InputPhase.EVENT;
                lastMerchantId = UUID.fromString(fields[1]);
                lastStreamType = fields[2];
                lastStreamId = UUID.fromString(fields[3]);
                lastStreamVersion = Long.parseLong(fields[4]);
                if (!fields[5].isEmpty()) {
                    throw new IllegalArgumentException("Unexpected checkpoint suffix");
                }
                return;
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Projection rebuild checkpoint is invalid", exception);
        }
        if (fields.length == 5 && "E".equals(fields[0])) {
            try {
                phase = InputPhase.EVENT;
                lastMerchantId = UUID.fromString(fields[1]);
                lastStreamType = fields[2];
                lastStreamId = UUID.fromString(fields[3]);
                lastStreamVersion = Long.parseLong(fields[4]);
                return;
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Projection rebuild checkpoint is invalid",
                        exception);
            }
        }
        throw new IllegalStateException("Projection rebuild checkpoint is invalid");
    }

    private enum InputPhase {
        BOOTSTRAP,
        EVENT
    }

    private record RebuildCursor(UUID cutoffManifestId, String lastCheckpointKey) {
    }
}
