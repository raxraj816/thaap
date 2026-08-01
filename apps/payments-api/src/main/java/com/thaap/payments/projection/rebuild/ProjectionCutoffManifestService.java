package com.thaap.payments.projection.rebuild;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProjectionCutoffManifestService {

    private static final String EMPTY_BOOTSTRAP_SHA256 = sha256("[]");

    private final JdbcTemplate jdbcTemplate;
    private final ProjectionBootstrapSourceReader bootstrapSourceReader;

    public ProjectionCutoffManifestService(JdbcTemplate jdbcTemplate,
            ProjectionBootstrapSourceReader bootstrapSourceReader) {
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapSourceReader = bootstrapSourceReader;
    }

    public FrozenCutoff freeze(UUID definitionId, String projectionName,
            String handlerVersion, Instant createdAt) {
        FrozenBootstrap bootstrap = requireOrCreateBootstrap(definitionId, createdAt);
        List<StreamCutoff> streams = jdbcTemplate.query("""
                SELECT head.merchant_id, head.stream_type, head.stream_id,
                       head.current_version, event.id, event.event_record_sha256,
                       COALESCE(fact.base_stream_version, 0)
                FROM event_stream_heads head
                JOIN domain_events event
                  ON event.merchant_id = head.merchant_id
                 AND event.stream_type = head.stream_type
                 AND event.stream_id = head.stream_id
                 AND event.stream_version = head.current_version
                LEFT JOIN projection_bootstrap_facts fact
                  ON fact.bootstrap_manifest_id = ?
                 AND fact.merchant_id = head.merchant_id
                 AND fact.source_type = 'PAYMENT_INTENT'
                 AND fact.source_id = head.stream_id
                 AND head.stream_type = 'PAYMENT'
                WHERE head.current_version > 0
                ORDER BY head.merchant_id, head.stream_type, head.stream_id
                """, (resultSet, rowNumber) -> new StreamCutoff(
                        resultSet.getObject(1, UUID.class), resultSet.getString(2),
                        resultSet.getObject(3, UUID.class), resultSet.getLong(4),
                        resultSet.getObject(5, UUID.class), resultSet.getString(6),
                        resultSet.getLong(7)), bootstrap.bootstrapManifestId());
        long eventCount = 0;
        StringBuilder canonical = new StringBuilder();
        canonical.append("projection=").append(projectionName)
                .append('\n').append("handler=").append(handlerVersion)
                .append('\n').append("origin=ALL")
                .append('\n').append("bootstrap=").append(bootstrap.canonicalSha256())
                .append('\n');
        for (StreamCutoff stream : streams) {
            if (stream.bootstrapBaseStreamVersion() > stream.maximumStreamVersion()) {
                throw new IllegalStateException("Bootstrap stream base is ahead of cutoff");
            }
            eventCount = Math.addExact(eventCount, stream.maximumStreamVersion()
                    - stream.bootstrapBaseStreamVersion());
            canonical.append(stream.merchantId()).append('|').append(stream.streamType())
                    .append('|').append(stream.streamId()).append('|')
                    .append(stream.maximumStreamVersion()).append('|')
                    .append(stream.terminalEventId()).append('|')
                    .append(stream.terminalRecordSha256()).append('\n');
        }
        String manifestSha256 = sha256(canonical.toString());
        List<FrozenCutoff> existing = jdbcTemplate.query("""
                SELECT id, bootstrap_manifest_id, canonical_manifest_sha256,
                       stream_count, event_count, bootstrap_fact_count
                FROM projection_cutoff_manifests
                WHERE projection_definition_id = ? AND canonical_manifest_sha256 = ?
                """, (resultSet, rowNumber) -> new FrozenCutoff(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class), resultSet.getString(3),
                        resultSet.getLong(4), resultSet.getLong(5),
                        resultSet.getLong(6)), definitionId, manifestSha256);
        if (existing.size() == 1) {
            return existing.getFirst();
        }
        if (!existing.isEmpty()) {
            throw new IllegalStateException("Projection cutoff identity is ambiguous");
        }
        UUID cutoffManifestId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO projection_cutoff_manifests (
                    id, projection_definition_id, bootstrap_manifest_id,
                    handler_version, traffic_origin_policy, stream_count,
                    event_count, bootstrap_fact_count, canonical_manifest_sha256,
                    bootstrap_manifest_sha256, created_at)
                VALUES (?, ?, ?, ?, 'ALL', ?, ?, ?, ?, ?, ?)
                """, cutoffManifestId, definitionId, bootstrap.bootstrapManifestId(), handlerVersion,
                streams.size(), eventCount, bootstrap.factCount(), manifestSha256,
                bootstrap.canonicalSha256(), Timestamp.from(createdAt));
        for (StreamCutoff stream : streams) {
            jdbcTemplate.update("""
                    INSERT INTO projection_cutoff_stream_positions (
                        id, cutoff_manifest_id, merchant_id, stream_type, stream_id,
                        maximum_stream_version, terminal_domain_event_id,
                        terminal_event_record_sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), cutoffManifestId, stream.merchantId(),
                    stream.streamType(), stream.streamId(), stream.maximumStreamVersion(),
                    stream.terminalEventId(), stream.terminalRecordSha256());
        }
        return new FrozenCutoff(cutoffManifestId, bootstrap.bootstrapManifestId(),
                manifestSha256, streams.size(), eventCount, bootstrap.factCount());
    }

    private FrozenBootstrap requireOrCreateBootstrap(UUID definitionId, Instant createdAt) {
        List<FrozenBootstrap> existing = jdbcTemplate.query("""
                SELECT id, canonical_manifest_sha256, fact_count
                FROM projection_bootstrap_manifests
                WHERE projection_definition_id = ?
                ORDER BY created_at, id
                """, (resultSet, rowNumber) -> new FrozenBootstrap(
                        resultSet.getObject(1, UUID.class), resultSet.getString(2),
                        resultSet.getLong(3)), definitionId);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        List<ProjectionBootstrapPaymentFact> paymentFacts =
                bootstrapSourceReader.readLegacyPayments(createdAt);
        List<EncodedBootstrapFact> encodedFacts = new ArrayList<>(paymentFacts.size());
        StringBuilder canonical = new StringBuilder("projection-bootstrap-v1\n");
        for (ProjectionBootstrapPaymentFact paymentFact : paymentFacts) {
            byte[] bytes = bootstrapSourceReader.encode(paymentFact);
            String digest = sha256(bytes);
            UUID factId = UUID.randomUUID();
            encodedFacts.add(new EncodedBootstrapFact(factId, paymentFact, bytes, digest));
            canonical.append(paymentFact.merchantId()).append('|')
                    .append("PAYMENT_INTENT|").append(paymentFact.paymentIntentId())
                    .append('|').append(paymentFact.baseStreamVersion()).append('|')
                    .append(paymentFact.baseTerminalDomainEventId()).append('|')
                    .append(paymentFact.baseTerminalEventRecordSha256()).append('|')
                    .append(digest).append('\n');
        }
        String digest = paymentFacts.isEmpty() ? EMPTY_BOOTSTRAP_SHA256
                : sha256(canonical.toString());
        UUID manifestId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO projection_bootstrap_manifests (
                    id, projection_definition_id, source_cutoff_at, fact_count,
                    canonical_manifest_sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, manifestId, definitionId, Timestamp.from(createdAt),
                encodedFacts.size(), digest, Timestamp.from(createdAt));
        for (EncodedBootstrapFact encoded : encodedFacts) {
            ProjectionBootstrapPaymentFact fact = encoded.fact();
            jdbcTemplate.update("""
                    INSERT INTO projection_bootstrap_facts (
                        id, bootstrap_manifest_id, merchant_id, source_type, source_id,
                        provenance, canonical_fact_bytes, canonical_fact_sha256,
                        source_cutoff_at, created_at, payment_intent_id,
                        base_stream_version, base_terminal_domain_event_id,
                        base_terminal_event_record_sha256)
                    VALUES (?, ?, ?, 'PAYMENT_INTENT', ?, 'SOURCE_SNAPSHOT', ?, ?, ?, ?,
                            ?, ?, ?, ?)
                    """, encoded.factId(), manifestId, fact.merchantId(),
                    fact.paymentIntentId(), encoded.bytes(), encoded.sha256(),
                    Timestamp.from(createdAt), Timestamp.from(createdAt),
                    fact.paymentIntentId(), fact.baseStreamVersion(),
                    fact.baseTerminalDomainEventId(),
                    fact.baseTerminalEventRecordSha256());
        }
        return new FrozenBootstrap(manifestId, digest, encodedFacts.size());
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record FrozenCutoff(UUID cutoffManifestId, UUID bootstrapManifestId,
            String canonicalManifestSha256, long streamCount, long eventCount,
            long bootstrapFactCount) {
        public long expectedInputCount() {
            return Math.addExact(eventCount, bootstrapFactCount);
        }
    }

    private record StreamCutoff(UUID merchantId, String streamType, UUID streamId,
            long maximumStreamVersion, UUID terminalEventId,
            String terminalRecordSha256, long bootstrapBaseStreamVersion) {
    }

    private record FrozenBootstrap(UUID bootstrapManifestId, String canonicalSha256,
            long factCount) {
    }

    private record EncodedBootstrapFact(UUID factId,
            ProjectionBootstrapPaymentFact fact, byte[] bytes, String sha256) {
        private EncodedBootstrapFact {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
