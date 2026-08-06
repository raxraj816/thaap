package com.thaap.payments.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.event.CanonicalDomainEventRecord;
import com.thaap.payments.event.CanonicalEventCodec;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.observability.DecisionIntelligenceMetrics;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.persistence.JdbcTime;
import com.thaap.payments.risk.RiskOperatorContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentTimelineService {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(PaymentTimelineService.class);

    private static final Map<DomainEventType, Set<String>> PRESENTATION_FIELDS = presentationFields();
    private static final Map<DomainEventType, Set<String>> HIDDEN_FIELDS = Map.of(
            DomainEventType.PAYMENT_INTENT_CREATED, Set.of("customerReference"),
            DomainEventType.MANUAL_REVIEW_ASSIGNED, Set.of("assigneeReference"),
            DomainEventType.FRAUD_LABEL_SUPERSEDED, Set.of("supersedesLabelReference"));

    private final PaymentIntentRepository paymentIntentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final CanonicalEventCodec eventCodec;
    private final WorkspaceCursorCodec cursorCodec;
    private final DecisionIntelligenceMetrics metrics;

    public PaymentTimelineService(PaymentIntentRepository paymentIntentRepository,
            JdbcTemplate jdbcTemplate, CanonicalEventCodec eventCodec,
            WorkspaceCursorCodec cursorCodec, DecisionIntelligenceMetrics metrics) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.eventCodec = eventCodec;
        this.cursorCodec = cursorCodec;
        this.metrics = metrics;
    }

    public TimelinePage timeline(RiskOperatorContext context, UUID paymentIntentId,
            Integer requestedLimit, String cursor) {
        requirePayment(context, paymentIntentId);
        int limit = boundedLimit(requestedLimit, 50);
        List<String> decoded = cursorCodec.decode("payment-timeline", context.merchantId(),
                paymentIntentId, cursor, 3);
        long maximumStreamVersion;
        long afterStreamVersion;
        UUID afterEventId;
        if (decoded == null) {
            Long maximum = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(stream_version), 0) FROM domain_events
                    WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                    """, Long.class, context.merchantId(), paymentIntentId);
            maximumStreamVersion = maximum == null ? 0 : maximum;
            afterStreamVersion = 0;
            afterEventId = new UUID(0, 0);
        } else {
            try {
                maximumStreamVersion = Long.parseLong(decoded.get(0));
                afterStreamVersion = Long.parseLong(decoded.get(1));
                afterEventId = UUID.fromString(decoded.get(2));
                if (maximumStreamVersion < 0 || afterStreamVersion < 0
                        || afterStreamVersion > maximumStreamVersion) {
                    throw invalidCursor();
                }
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw invalidCursor();
            }
        }
        List<TimelineEntry> entries = jdbcTemplate.query("""
                SELECT id, event_version, event_record_bytes, event_record_sha256
                FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                  AND stream_version > ? AND stream_version <= ?
                ORDER BY stream_version, id LIMIT ?
                """, (rows, number) -> map(rows.getObject(1, UUID.class), rows.getInt(2),
                        rows.getBytes(3), rows.getString(4), paymentIntentId),
                context.merchantId(), paymentIntentId, afterStreamVersion,
                maximumStreamVersion, limit + 1);
        String nextCursor = null;
        if (entries.size() > limit) {
            TimelineEntry last = entries.get(limit - 1);
            nextCursor = cursorCodec.encode("payment-timeline", context.merchantId(),
                    paymentIntentId, List.of(Long.toString(maximumStreamVersion),
                            Long.toString(last.streamVersion()), last.eventId().toString()));
            entries = List.copyOf(entries.subList(0, limit));
        }
        metrics.queryResult("TIMELINE", entries.size(), "COMPLETE");
        return new TimelinePage(maximumStreamVersion, entries, nextCursor,
                "STREAM_VERSION_ASC", InvestigationSectionState.AVAILABLE);
    }

    public WebhookActivityPage webhookActivity(RiskOperatorContext context,
            UUID paymentIntentId, Integer requestedLimit, String cursor) {
        requirePayment(context, paymentIntentId);
        int limit = boundedLimit(requestedLimit, 50);
        List<String> decoded = cursorCodec.decode("payment-webhooks", context.merchantId(),
                paymentIntentId, cursor, 2);
        Instant afterTime = decoded == null ? Instant.EPOCH : Instant.parse(decoded.get(0));
        UUID afterId = decoded == null ? new UUID(0, 0) : UUID.fromString(decoded.get(1));
        List<WebhookActivity> rows = jdbcTemplate.query("""
                SELECT id, event_type, status, attempt_count, created_at,
                       last_attempt_at, delivered_at, last_error_code
                FROM webhook_deliveries
                WHERE merchant_id = ? AND resource_id = ?
                  AND (created_at > ? OR (created_at = ? AND id > ?))
                ORDER BY created_at, id LIMIT ?
                """, (result, number) -> new WebhookActivity(
                        result.getObject(1, UUID.class), result.getString(2),
                        result.getString(3), result.getInt(4), JdbcTime.instant(result, 5),
                        JdbcTime.instant(result, 6), JdbcTime.instant(result, 7),
                        result.getString(8)), context.merchantId(), paymentIntentId,
                Timestamp.from(afterTime), Timestamp.from(afterTime), afterId, limit + 1);
        String nextCursor = null;
        if (rows.size() > limit) {
            WebhookActivity last = rows.get(limit - 1);
            nextCursor = cursorCodec.encode("payment-webhooks", context.merchantId(),
                    paymentIntentId, List.of(last.createdAt().toString(), last.deliveryId().toString()));
            rows = List.copyOf(rows.subList(0, limit));
        }
        metrics.queryResult("WEBHOOK_ACTIVITY", rows.size(), "COMPLETE");
        return new WebhookActivityPage(rows, nextCursor, "WEBHOOK_TRANSPORT_EVIDENCE");
    }

    private TimelineEntry map(UUID databaseEventId, int databaseVersion,
            byte[] recordBytes, String recordSha256, UUID paymentIntentId) {
        CanonicalDomainEventRecord event = eventCodec.decodeAndValidate(recordBytes, recordSha256);
        if (!databaseEventId.equals(event.eventId()) || databaseVersion != event.eventVersion()
                || !paymentIntentId.equals(event.streamId())) {
            // The signed record disagrees with the row that carries it. History is append-only,
            // so this never heals; page a human rather than invite an endless client retry.
            LOGGER.error("Timeline evidence integrity check failed eventId={} streamId={} "
                    + "databaseVersion={} recordVersion={}",
                    databaseEventId, paymentIntentId, databaseVersion, event.eventVersion());
            throw new PublicApiException(
                    PublicApiErrorDescriptor.TIMELINE_EVIDENCE_INTEGRITY_FAILED);
        }
        Map<String, Object> safeFacts = safeFacts(event.eventType(), event.eventVersion(), event.payload());
        return new TimelineEntry(event.streamVersion(), event.eventId(),
                event.eventType().wireValue(), event.eventVersion(), event.occurredAt(),
                event.recordedAt(), event.actor() == null ? null : event.actor().type().name(),
                event.trafficOrigin() == null ? null : event.trafficOrigin().name(),
                category(event.eventType()), "timeline." + event.eventType().wireValue(),
                safeFacts, List.of("/workspace/payments/" + paymentIntentId));
    }

    private Map<String, Object> safeFacts(DomainEventType eventType, int eventVersion,
            ObjectNode payload) {
        if (eventVersion != 1 || !PRESENTATION_FIELDS.containsKey(eventType)) {
            throw unsupportedPresentationEvent();
        }
        Set<String> visible = PRESENTATION_FIELDS.get(eventType);
        Set<String> hidden = HIDDEN_FIELDS.getOrDefault(eventType, Set.of());
        Map<String, Object> safe = new LinkedHashMap<>();
        payload.fields().forEachRemaining(field -> {
            if (visible.contains(field.getKey())) {
                safe.put(field.getKey(), jsonValue(field.getValue()));
            } else if (!hidden.contains(field.getKey())) {
                throw unsupportedPresentationEvent();
            }
        });
        return Map.copyOf(safe);
    }

    private static Map<DomainEventType, Set<String>> presentationFields() {
        EnumMap<DomainEventType, Set<String>> fields = new EnumMap<>(DomainEventType.class);
        fields.put(DomainEventType.PAYMENT_INTENT_CREATED,
                Set.of("amountMinor", "currency", "captureMethod"));
        fields.put(DomainEventType.PAYMENT_INTENT_CANCELED, Set.of("cancellationReason"));
        fields.put(DomainEventType.PAYMENT_ATTEMPT_COMPLETED,
                Set.of("attemptNumber", "outcome", "processorCode"));
        fields.put(DomainEventType.FRAUD_DECISION_CREATED,
                Set.of("action", "riskLevel", "reasonCodes", "rulesetVersion",
                        "riskScore", "featureCatalogueVersion"));
        fields.put(DomainEventType.AUTHENTICATION_CHALLENGE_CREATED,
                Set.of("challengeType", "expiresAt"));
        fields.put(DomainEventType.AUTHENTICATION_CHALLENGE_COMPLETED,
                Set.of("result", "challengeStatus"));
        fields.put(DomainEventType.AUTHORIZATION_CREATED,
                Set.of("requestedAmountMinor", "authorizedAmountMinor", "currency", "outcome"));
        fields.put(DomainEventType.AUTHORIZATION_VOIDED,
                Set.of("reasonCode", "voidedAmountMinor", "currency"));
        fields.put(DomainEventType.CAPTURE_CREATED, Set.of("amountMinor", "currency"));
        fields.put(DomainEventType.REFUND_CREATED,
                Set.of("amountMinor", "currency", "reasonCode"));
        fields.put(DomainEventType.CHARGEBACK_CREATED,
                Set.of("amountMinor", "currency", "reasonCode"));
        fields.put(DomainEventType.MANUAL_REVIEW_OPENED, Set.of("priority", "reasonCodes"));
        fields.put(DomainEventType.MANUAL_REVIEW_ASSIGNED, Set.of("assignmentEpoch"));
        fields.put(DomainEventType.MANUAL_REVIEW_RESOLVED, Set.of("resolution", "fraudType"));
        fields.put(DomainEventType.FRAUD_LABEL_RECORDED,
                Set.of("label", "source", "availableAt", "fraudType"));
        fields.put(DomainEventType.FRAUD_LABEL_SUPERSEDED,
                Set.of("label", "source", "availableAt", "fraudType"));
        fields.put(DomainEventType.POST_AUTHORIZATION_SIGNAL_RECORDED,
                Set.of("signalType", "recommendation", "reasonCodes"));
        return Collections.unmodifiableMap(fields);
    }

    /**
     * The stored event is intact but this build has no reviewed safe presentation for it, which a
     * later deployment can fix without the caller changing anything. That is genuinely transient.
     */
    private ResponseStatusException unsupportedPresentationEvent() {
        return new PublicApiException(PublicApiErrorDescriptor.TIMELINE_INTEGRITY_UNAVAILABLE);
    }

    private Object jsonValue(JsonNode node) {
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(jsonValue(value)));
            return List.copyOf(values);
        }
        throw unsupportedPresentationEvent();
    }

    private String category(DomainEventType type) {
        return switch (type) {
            case PAYMENT_INTENT_CREATED, PAYMENT_INTENT_CANCELED,
                    PAYMENT_ATTEMPT_COMPLETED -> "PAYMENT";
            case FRAUD_DECISION_CREATED -> "DECISION";
            case AUTHORIZATION_CREATED, AUTHORIZATION_VOIDED, CAPTURE_CREATED,
                    REFUND_CREATED -> "MONEY";
            case CHARGEBACK_CREATED -> "DISPUTE";
            case AUTHENTICATION_CHALLENGE_CREATED,
                    AUTHENTICATION_CHALLENGE_COMPLETED -> "AUTHENTICATION";
            case MANUAL_REVIEW_OPENED, MANUAL_REVIEW_ASSIGNED,
                    MANUAL_REVIEW_RESOLVED -> "REVIEW";
            case FRAUD_LABEL_RECORDED, FRAUD_LABEL_SUPERSEDED -> "LABEL";
            case POST_AUTHORIZATION_SIGNAL_RECORDED -> "POST_AUTHORIZATION";
            default -> throw new PublicApiException(
                    PublicApiErrorDescriptor.TIMELINE_INTEGRITY_UNAVAILABLE);
        };
    }

    private void requirePayment(RiskOperatorContext context, UUID paymentIntentId) {
        if (!paymentIntentRepository.findByIdAndMerchantId(paymentIntentId,
                context.merchantId()).isPresent()) {
            throw new PublicApiException(
                    PublicApiErrorDescriptor.PAYMENT_INVESTIGATION_NOT_FOUND);
        }
    }

    private int boundedLimit(Integer requested, int maximum) {
        int limit = requested == null ? maximum : requested;
        if (limit < 1 || limit > maximum) {
            throw new PublicApiException(PublicApiErrorDescriptor.WORKSPACE_PAGE_SIZE_INVALID);
        }
        return limit;
    }

    private ResponseStatusException invalidCursor() {
        return new PublicApiException(PublicApiErrorDescriptor.TIMELINE_CURSOR_INVALID);
    }

    public record TimelinePage(long maximumStreamVersion, List<TimelineEntry> data,
            String nextCursor, String ordering, InvestigationSectionState state) {
    }

    public record TimelineEntry(long streamVersion, UUID eventId, String eventType,
            int eventVersion, Instant occurredAt, Instant recordedAt, String actorType,
            String trafficOrigin, String category, String titleCode,
            Map<String, Object> safeFacts, List<String> sourceLinks) {
    }

    public record WebhookActivityPage(List<WebhookActivity> data, String nextCursor,
            String authority) {
    }

    public record WebhookActivity(UUID deliveryId, String eventType, String status,
            int attemptCount, Instant createdAt, Instant lastAttemptAt,
            Instant deliveredAt, String safeFailureCode) {
    }
}
