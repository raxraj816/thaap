package com.thaap.payments.investigation;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PaymentInvestigationFilterCodec {

    public static final String SCHEMA_VERSION = "payment_investigation_filter_v1";
    private static final Duration MAXIMUM_RANGE = Duration.ofDays(90);
    private static final int MAXIMUM_SET_VALUES = 64;

    private final ObjectMapper objectMapper;

    public PaymentInvestigationFilterCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedFilter normalize(PaymentInvestigationFilter raw, Instant now) {
        PaymentInvestigationFilter source = raw == null ? empty() : raw;
        Set<com.thaap.payments.payment.PaymentIntentStatus> lifecycle = enums(source.lifecycleStatuses());
        Set<com.thaap.payments.fraud.FraudDecisionAction> actions = enums(source.fraudActions());
        Set<com.thaap.payments.fraud.FraudRiskLevel> levels = enums(source.riskLevels());
        Set<com.thaap.payments.fraud.FraudDecisionReasonCode> reasons = enums(source.reasonCodes());
        Set<com.thaap.payments.review.ManualReviewCaseStatus> reviewStatuses = enums(source.reviewStatuses());
        Set<com.thaap.payments.review.ReviewResolutionOutcome> reviewOutcomes = enums(source.reviewOutcomes());
        Set<com.thaap.payments.review.FraudLabelValue> labels = enums(source.labelValues());
        Set<com.thaap.payments.review.FraudType> fraudTypes = enums(source.fraudTypes());
        Set<com.thaap.payments.tenant.TrafficOrigin> origins = enums(source.trafficOrigins());
        Set<String> currencies = normalizeCurrencies(source.currencies());
        int setValues = lifecycle.size() + actions.size() + levels.size() + reasons.size()
                + reviewStatuses.size() + reviewOutcomes.size() + labels.size()
                + fraudTypes.size() + origins.size() + currencies.size();
        if (setValues > MAXIMUM_SET_VALUES) {
            throw invalid("Payment investigation filter exceeds 64 set values");
        }
        Long minimum = source.minimumAmountMinor();
        Long maximum = source.maximumAmountMinor();
        if ((minimum != null && minimum < 0) || (maximum != null && maximum < 0)
                || (minimum != null && maximum != null && minimum > maximum)) {
            throw invalid("Payment investigation amount band is invalid");
        }
        Instant from = canonical(source.createdFrom());
        Instant to = canonical(source.createdTo());
        if ((from == null) != (to == null)) {
            throw invalid("Payment investigation range requires both start and end");
        }
        if (from != null && (!from.isBefore(to)
                || Duration.between(from, to).compareTo(MAXIMUM_RANGE) > 0
                || to.isAfter(canonical(now)))) {
            throw invalid("Payment investigation range is invalid or exceeds 90 days");
        }
        PaymentInvestigationFilter normalized = new PaymentInvestigationFilter(
                lifecycle, actions, levels, reasons, reviewStatuses, reviewOutcomes,
                labels, fraudTypes, origins, currencies, minimum, maximum, from, to);
        return new NormalizedFilter(normalized, canonicalJson(normalized));
    }

    public PaymentInvestigationFilter decodeStored(String schemaVersion,
            String canonicalJson, Instant now) {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new PaymentSavedViewUpgradeRequiredException();
        }
        try {
            JsonNode root = objectMapper.readTree(canonicalJson);
            if (!(root instanceof ObjectNode object)) {
                throw new PaymentSavedViewUpgradeRequiredException();
            }
            PaymentInvestigationFilter decoded = new PaymentInvestigationFilter(
                    enumValues(object, "lifecycleStatuses", com.thaap.payments.payment.PaymentIntentStatus::valueOf),
                    enumValues(object, "fraudActions", com.thaap.payments.fraud.FraudDecisionAction::valueOf),
                    enumValues(object, "riskLevels", com.thaap.payments.fraud.FraudRiskLevel::valueOf),
                    enumValues(object, "reasonCodes", com.thaap.payments.fraud.FraudDecisionReasonCode::valueOf),
                    enumValues(object, "reviewStatuses", com.thaap.payments.review.ManualReviewCaseStatus::valueOf),
                    enumValues(object, "reviewOutcomes", com.thaap.payments.review.ReviewResolutionOutcome::valueOf),
                    enumValues(object, "labelValues", com.thaap.payments.review.FraudLabelValue::valueOf),
                    enumValues(object, "fraudTypes", com.thaap.payments.review.FraudType::valueOf),
                    enumValues(object, "trafficOrigins", com.thaap.payments.tenant.TrafficOrigin::valueOf),
                    strings(object, "currencies"), nullableLong(object, "minimumAmountMinor"),
                    nullableLong(object, "maximumAmountMinor"), instant(object, "createdFrom"),
                    instant(object, "createdTo"));
            NormalizedFilter normalized = normalize(decoded, now);
            if (!MessageDigest.isEqual(canonicalJson.getBytes(StandardCharsets.UTF_8),
                    normalized.canonicalJson().getBytes(StandardCharsets.UTF_8))) {
                throw new PaymentSavedViewUpgradeRequiredException();
            }
            return normalized.filter();
        } catch (PaymentSavedViewUpgradeRequiredException exception) {
            throw exception;
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new PaymentSavedViewUpgradeRequiredException();
        }
    }

    public String columnsJson(Set<PaymentInvestigationColumn> rawColumns) {
        Set<PaymentInvestigationColumn> columns = enums(rawColumns);
        if (columns.isEmpty()) {
            columns = Set.of(PaymentInvestigationColumn.CREATED_AT,
                    PaymentInvestigationColumn.PAYMENT_STATUS,
                    PaymentInvestigationColumn.AMOUNT,
                    PaymentInvestigationColumn.FRAUD_ACTION,
                    PaymentInvestigationColumn.REVIEW_STATUS,
                    PaymentInvestigationColumn.COVERAGE);
        }
        ArrayNode array = objectMapper.createArrayNode();
        columns.stream().map(Enum::name).sorted().forEach(array::add);
        return write(array);
    }

    public Set<PaymentInvestigationColumn> decodeColumns(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                throw new PaymentSavedViewUpgradeRequiredException();
            }
            Set<PaymentInvestigationColumn> columns = new LinkedHashSet<>();
            node.forEach(value -> columns.add(PaymentInvestigationColumn.valueOf(value.asText())));
            if (columns.isEmpty() || !columnsJson(columns).equals(json)) {
                throw new PaymentSavedViewUpgradeRequiredException();
            }
            return Set.copyOf(columns);
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new PaymentSavedViewUpgradeRequiredException();
        }
    }

    public String digest(String canonicalJson, PaymentInvestigationSort sort,
            String columnsJson) {
        try {
            String source = SCHEMA_VERSION + "\n" + canonicalJson + "\n"
                    + sort.name() + "\n" + columnsJson;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String filterDigest(PaymentInvestigationFilter filter, Instant now) {
        return digest(normalize(filter, now).canonicalJson(),
                PaymentInvestigationSort.CREATED_AT_DESC, "[]");
    }

    private String canonicalJson(PaymentInvestigationFilter filter) {
        ObjectNode root = objectMapper.createObjectNode();
        addEnums(root, "lifecycleStatuses", filter.lifecycleStatuses());
        addEnums(root, "fraudActions", filter.fraudActions());
        addEnums(root, "riskLevels", filter.riskLevels());
        addEnums(root, "reasonCodes", filter.reasonCodes());
        addEnums(root, "reviewStatuses", filter.reviewStatuses());
        addEnums(root, "reviewOutcomes", filter.reviewOutcomes());
        addEnums(root, "labelValues", filter.labelValues());
        addEnums(root, "fraudTypes", filter.fraudTypes());
        addEnums(root, "trafficOrigins", filter.trafficOrigins());
        ArrayNode currencies = root.putArray("currencies");
        filter.currencies().stream().sorted().forEach(currencies::add);
        nullable(root, "minimumAmountMinor", filter.minimumAmountMinor());
        nullable(root, "maximumAmountMinor", filter.maximumAmountMinor());
        nullable(root, "createdFrom", filter.createdFrom());
        nullable(root, "createdTo", filter.createdTo());
        return write(root);
    }

    private void addEnums(ObjectNode root, String name, Set<? extends Enum<?>> values) {
        ArrayNode array = root.putArray(name);
        values.stream().map(Enum::name).sorted().forEach(array::add);
    }

    private void nullable(ObjectNode root, String name, Object value) {
        if (value == null) root.putNull(name);
        else if (value instanceof Long number) root.put(name, number);
        else root.put(name, value.toString());
    }

    private <E extends Enum<E>> Set<E> enumValues(ObjectNode root, String field,
            Function<String, E> parser) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) throw new PaymentSavedViewUpgradeRequiredException();
        Set<E> result = new LinkedHashSet<>();
        node.forEach(value -> result.add(parser.apply(value.asText())));
        return Set.copyOf(result);
    }

    private Set<String> strings(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) throw new PaymentSavedViewUpgradeRequiredException();
        Set<String> result = new LinkedHashSet<>();
        node.forEach(value -> result.add(value.asText()));
        return Set.copyOf(result);
    }

    private Long nullableLong(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.longValue();
    }

    private Instant instant(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : Instant.parse(node.asText());
    }

    private Set<String> normalizeCurrencies(Set<String> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || !value.trim().toUpperCase(Locale.ROOT).matches("[A-Z]{3}")) {
                throw invalid("Payment investigation currency is invalid");
            }
            normalized.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return normalized.stream().sorted().collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private <E extends Enum<E>> Set<E> enums(Set<E> raw) {
        if (raw == null || raw.isEmpty()) return Set.of();
        if (raw.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("Payment investigation filter sets cannot contain null");
        }
        return raw.stream().sorted(Comparator.comparing(Enum::name))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private PaymentInvestigationFilter empty() {
        return new PaymentInvestigationFilter(Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                null, null, null, null);
    }

    private Instant canonical(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payment investigation filter cannot be serialized", exception);
        }
    }

    private ResponseStatusException invalid(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.INVESTIGATION_FILTER_INVALID);
    }

    public record NormalizedFilter(PaymentInvestigationFilter filter, String canonicalJson) {
    }
}
