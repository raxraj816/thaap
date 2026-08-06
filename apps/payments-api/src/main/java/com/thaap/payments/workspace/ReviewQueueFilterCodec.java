package com.thaap.payments.workspace;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.review.ReviewStage;
import com.thaap.payments.tenant.TrafficOrigin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReviewQueueFilterCodec {

    public static final String SCHEMA_VERSION = "review_queue_filter_v1";
    private static final int MAXIMUM_TERMS = 20;
    private static final int MAXIMUM_SET_VALUES = 50;
    private static final Duration MAXIMUM_INTERVAL = Duration.ofDays(90);

    private final ObjectMapper objectMapper;

    public ReviewQueueFilterCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedFilter normalize(ReviewQueueFilter raw, Instant now) {
        ReviewQueueFilter source = raw == null
                ? new ReviewQueueFilter(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                        Set.of(), Set.of(), null, null)
                : raw;
        Set<ManualReviewCaseStatus> statuses = enumSet(source.statuses());
        Set<ReviewStage> reviewStages = enumSet(source.reviewStages());
        Set<FraudDecisionAction> decisionActions = enumSet(source.decisionActions());
        Set<FraudDecisionReasonCode> reasonCodes = enumSet(source.reasonCodes());
        Set<TrafficOrigin> trafficOrigins = enumSet(source.trafficOrigins());
        Set<UUID> assignedOperatorIds = valueSet(source.assignedOperatorIds(), Comparator.naturalOrder());
        Set<ReviewSlaState> slaStates = enumSet(source.slaStates());
        Instant from = canonical(source.createdFrom());
        Instant to = canonical(source.createdTo());
        int terms = nonEmpty(statuses) + nonEmpty(reviewStages) + nonEmpty(decisionActions)
                + nonEmpty(reasonCodes) + nonEmpty(trafficOrigins) + nonEmpty(assignedOperatorIds)
                + nonEmpty(slaStates) + (from == null ? 0 : 1) + (to == null ? 0 : 1);
        if (terms > MAXIMUM_TERMS) {
            throw invalid("Review queue filter exceeds 20 terms");
        }
        int values = statuses.size() + reviewStages.size() + decisionActions.size()
                + reasonCodes.size() + trafficOrigins.size() + assignedOperatorIds.size()
                + slaStates.size();
        if (values > MAXIMUM_SET_VALUES) {
            throw invalid("Review queue filter exceeds 50 set values");
        }
        if ((from == null) != (to == null)) {
            throw invalid("Review queue interval requires both start and end");
        }
        if (from != null) {
            if (!from.isBefore(to)) {
                throw invalid("Review queue interval must be half-open with start before end");
            }
            if (Duration.between(from, to).compareTo(MAXIMUM_INTERVAL) > 0) {
                throw invalid("Review queue interval cannot exceed 90 days");
            }
        }
        if (to != null && to.isAfter(canonical(now))) {
            throw invalid("Review queue interval cannot end in the future");
        }
        ReviewQueueFilter normalized = new ReviewQueueFilter(statuses, reviewStages,
                decisionActions, reasonCodes, trafficOrigins, assignedOperatorIds, slaStates,
                from, to);
        return new NormalizedFilter(normalized, canonicalJson(normalized));
    }

    public ReviewQueueFilter decodeStored(String schemaVersion, String canonicalJson, Instant now) {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new SavedSearchUpgradeRequiredException();
        }
        try {
            JsonNode root = objectMapper.readTree(canonicalJson);
            if (root == null || !root.isObject()) {
                throw new SavedSearchUpgradeRequiredException();
            }
            ReviewQueueFilter decoded = new ReviewQueueFilter(
                    enums(root, "statuses", ManualReviewCaseStatus::valueOf),
                    enums(root, "reviewStages", ReviewStage::valueOf),
                    enums(root, "decisionActions", FraudDecisionAction::valueOf),
                    enums(root, "reasonCodes", FraudDecisionReasonCode::valueOf),
                    enums(root, "trafficOrigins", TrafficOrigin::valueOf),
                    uuids(root, "assignedOperatorIds"),
                    enums(root, "slaStates", ReviewSlaState::valueOf),
                    instant(root, "createdFrom"), instant(root, "createdTo"));
            NormalizedFilter normalized = normalize(decoded, now);
            if (!MessageDigest.isEqual(canonicalJson.getBytes(StandardCharsets.UTF_8),
                    normalized.canonicalJson().getBytes(StandardCharsets.UTF_8))) {
                throw new SavedSearchUpgradeRequiredException();
            }
            return normalized.filter();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new SavedSearchUpgradeRequiredException();
        }
    }

    public String visibleColumnsJson(Set<ReviewQueueColumn> rawColumns) {
        Set<ReviewQueueColumn> columns = enumSet(rawColumns);
        if (columns.isEmpty()) {
            columns = Set.of(ReviewQueueColumn.CREATED_AT, ReviewQueueColumn.STATUS,
                    ReviewQueueColumn.SLA_STATE, ReviewQueueColumn.ASSIGNED_OPERATOR,
                    ReviewQueueColumn.REASON_CODES);
        }
        ArrayNode array = objectMapper.createArrayNode();
        columns.stream().map(Enum::name).sorted().forEach(array::add);
        return write(array);
    }

    public Set<ReviewQueueColumn> decodeVisibleColumns(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                throw new SavedSearchUpgradeRequiredException();
            }
            Set<ReviewQueueColumn> columns = new LinkedHashSet<>();
            node.forEach(value -> columns.add(ReviewQueueColumn.valueOf(value.asText())));
            if (columns.isEmpty() || !visibleColumnsJson(columns).equals(json)) {
                throw new SavedSearchUpgradeRequiredException();
            }
            return Set.copyOf(columns);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new SavedSearchUpgradeRequiredException();
        }
    }

    public String digest(String canonicalFilterJson, ReviewQueueSort sort, String columnsJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String definition = SCHEMA_VERSION + "\n" + canonicalFilterJson + "\n"
                    + sort.name() + "\n" + columnsJson;
            return HexFormat.of().formatHex(digest.digest(definition.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String canonicalJson(ReviewQueueFilter filter) {
        ObjectNode root = objectMapper.createObjectNode();
        addEnums(root, "statuses", filter.statuses());
        addEnums(root, "reviewStages", filter.reviewStages());
        addEnums(root, "decisionActions", filter.decisionActions());
        addEnums(root, "reasonCodes", filter.reasonCodes());
        addEnums(root, "trafficOrigins", filter.trafficOrigins());
        ArrayNode operators = root.putArray("assignedOperatorIds");
        filter.assignedOperatorIds().stream().map(UUID::toString).sorted().forEach(operators::add);
        addEnums(root, "slaStates", filter.slaStates());
        if (filter.createdFrom() == null) {
            root.putNull("createdFrom");
        } else {
            root.put("createdFrom", filter.createdFrom().toString());
        }
        if (filter.createdTo() == null) {
            root.putNull("createdTo");
        } else {
            root.put("createdTo", filter.createdTo().toString());
        }
        return write(root);
    }

    private void addEnums(ObjectNode root, String name, Set<? extends Enum<?>> values) {
        ArrayNode array = root.putArray(name);
        values.stream().map(Enum::name).sorted().forEach(array::add);
    }

    private <E extends Enum<E>> Set<E> enums(JsonNode root, String field, Function<String, E> parser) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            throw new SavedSearchUpgradeRequiredException();
        }
        Set<E> result = new LinkedHashSet<>();
        node.forEach(value -> result.add(parser.apply(value.asText())));
        return Set.copyOf(result);
    }

    private Set<UUID> uuids(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            throw new SavedSearchUpgradeRequiredException();
        }
        Set<UUID> result = new LinkedHashSet<>();
        node.forEach(value -> result.add(UUID.fromString(value.asText())));
        return Set.copyOf(result);
    }

    private Instant instant(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : Instant.parse(node.asText());
    }

    private <E extends Enum<E>> Set<E> enumSet(Set<E> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        if (raw.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("Review queue filter sets must not contain null");
        }
        return raw.stream().sorted(Comparator.comparing(Enum::name))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private <T> Set<T> valueSet(Set<T> raw, Comparator<T> comparator) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        if (raw.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("Review queue filter sets must not contain null");
        }
        return raw.stream().sorted(comparator)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private int nonEmpty(Set<?> values) {
        return values.isEmpty() ? 0 : 1;
    }

    private Instant canonical(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Review queue filter could not be serialized", exception);
        }
    }

    private ResponseStatusException invalid(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.REVIEW_QUEUE_FILTER_INVALID, message);
    }

    public record NormalizedFilter(ReviewQueueFilter filter, String canonicalJson) {
    }
}
