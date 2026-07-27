package com.thaap.payments.controls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.fraud.FeatureCatalogue;
import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.security.SecretHasher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthoredRuleCodec {

    public static final String SCHEMA_VERSION = "authored_rule_v1";
    public static final int MAX_CANONICAL_BYTES = 8_192;
    public static final int MAX_DEPTH = 3;
    public static final int MAX_PREDICATES = 32;
    public static final int MAX_SET_SIZE = 20;
    public static final int MAX_STRING_BYTES = 128;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<String> GROUP_KEYS = Set.of("all", "any");
    private static final Set<String> PREDICATE_KEYS = Set.of("feature", "operator", "value");

    private final ObjectMapper objectMapper;

    public AuthoredRuleCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EncodedRule encode(Map<String, Object> input) {
        Counter counter = new Counter();
        AuthoredRuleNode node = parseNode(input, 1, counter);
        if (counter.predicates == 0) {
            throw invalid("Authored rule must contain at least one predicate");
        }
        Object canonicalValue = canonicalValue(node);
        String canonicalJson = write(canonicalValue);
        if (canonicalJson.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_BYTES) {
            throw invalid("Canonical authored rule exceeds 8192 bytes");
        }
        return new EncodedRule(node, canonicalJson, SecretHasher.sha256(canonicalJson), counter.predicates);
    }

    public EncodedRule parseStored(String canonicalJson, String digest) {
        if (canonicalJson == null || !SecretHasher.sha256(canonicalJson).equals(digest)) {
            throw new IllegalStateException("Authored rule digest does not match stored bytes");
        }
        try {
            EncodedRule encoded = encode(objectMapper.readValue(canonicalJson, MAP));
            if (!encoded.canonicalJson().equals(canonicalJson)) {
                throw new IllegalStateException("Stored authored rule is not canonical");
            }
            return encoded;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored authored rule is unreadable", exception);
        } catch (ResponseStatusException exception) {
            throw new IllegalStateException("Stored authored rule violates its schema", exception);
        }
    }

    private AuthoredRuleNode parseNode(Object input, int depth, Counter counter) {
        if (!(input instanceof Map<?, ?> raw)) {
            throw invalid("Every authored rule node must be an object");
        }
        Map<String, Object> map = stringMap(raw);
        if (map.keySet().equals(Set.of("all")) || map.keySet().equals(Set.of("any"))) {
            if (depth > MAX_DEPTH) {
                throw invalid("Authored rule nesting exceeds depth 3");
            }
            String key = map.containsKey("all") ? "all" : "any";
            Object clausesValue = map.get(key);
            if (!(clausesValue instanceof List<?> clauses) || clauses.isEmpty()) {
                throw invalid("Rule groups require a non-empty clause list");
            }
            List<AuthoredRuleNode> parsed = new ArrayList<>();
            for (Object clause : clauses) {
                parsed.add(parseNode(clause, depth + 1, counter));
            }
            return new AuthoredRuleNode.Group("all".equals(key)
                    ? AuthoredRuleNode.Mode.ALL : AuthoredRuleNode.Mode.ANY, parsed);
        }
        if (!map.keySet().equals(PREDICATE_KEYS) || map.keySet().stream().anyMatch(GROUP_KEYS::contains)) {
            throw invalid("Rule nodes use exactly one group key or feature/operator/value");
        }
        counter.predicates++;
        if (counter.predicates > MAX_PREDICATES) {
            throw invalid("Authored rule exceeds 32 predicates");
        }
        if (!(map.get("feature") instanceof String feature) || !FeatureCatalogue.requiredCodes().contains(feature)) {
            throw invalid("Predicate feature is not in the governed catalogue");
        }
        AuthoredRuleOperator operator;
        try {
            operator = AuthoredRuleOperator.valueOf(requireString(map.get("operator"), "operator"));
        } catch (IllegalArgumentException exception) {
            throw invalid("Predicate operator is unsupported");
        }
        Object operand = normalizeOperand(feature, FeatureCatalogue.requiredType(feature),
                operator, map.get("value"));
        return new AuthoredRuleNode.Predicate(feature, operator, operand);
    }

    private Object normalizeOperand(String feature, FeatureValueType type,
            AuthoredRuleOperator operator, Object value) {
        return switch (type) {
            case LONG -> normalizeLong(feature, operator, value);
            case BOOLEAN -> {
                if (operator != AuthoredRuleOperator.EQ || !(value instanceof Boolean)) {
                    throw invalid("Boolean predicates support EQ with a boolean operand");
                }
                yield value;
            }
            case STRING -> normalizeString(operator, value);
            case DECIMAL -> throw invalid("Decimal features are not available to authored rules");
        };
    }

    private Object normalizeLong(String feature, AuthoredRuleOperator operator, Object value) {
        if (operator == AuthoredRuleOperator.IN) {
            return normalizeSet(value, operand -> boundedLong(feature, operand));
        }
        if (!(operator == AuthoredRuleOperator.EQ || operator == AuthoredRuleOperator.GT
                || operator == AuthoredRuleOperator.GTE || operator == AuthoredRuleOperator.LT
                || operator == AuthoredRuleOperator.LTE)) {
            throw invalid("Numeric predicate operator is unsupported");
        }
        return boundedLong(feature, value);
    }

    private Long boundedLong(String feature, Object value) {
        Long operand = exactLong(value);
        long minimum = feature.equals("payment.amount_minor")
                || feature.equals("payment.attempt_number") ? 1L : 0L;
        long maximum = feature.equals("risk.bot_score_bps") ? 10_000L : Long.MAX_VALUE;
        if (operand < minimum || operand > maximum) {
            throw invalid("Numeric operand is outside the governed feature range");
        }
        return operand;
    }

    private Object normalizeString(AuthoredRuleOperator operator, Object value) {
        if (operator == AuthoredRuleOperator.IN) {
            return normalizeSet(value, this::boundedString);
        }
        if (operator != AuthoredRuleOperator.EQ) {
            throw invalid("String predicates support only EQ or IN");
        }
        return boundedString(value);
    }

    private List<Object> normalizeSet(Object value, java.util.function.Function<Object, Object> normalizer) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_SET_SIZE) {
            throw invalid("IN requires between 1 and 20 operands");
        }
        List<Object> normalized = list.stream().map(normalizer).distinct()
                .sorted(Comparator.comparing(this::write)).toList();
        if (normalized.size() != list.size()) {
            throw invalid("IN operands must be unique");
        }
        return normalized;
    }

    private Long exactLong(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw invalid("Numeric operands must be exact 64-bit integers");
    }

    private String boundedString(Object value) {
        String string = requireString(value, "value");
        int bytes = string.getBytes(StandardCharsets.UTF_8).length;
        if (string.isBlank() || bytes > MAX_STRING_BYTES || string.codePoints().anyMatch(this::forbiddenCodePoint)) {
            throw invalid("String operands must be safe non-blank UTF-8 values of at most 128 bytes");
        }
        return string;
    }

    private boolean forbiddenCodePoint(int codePoint) {
        return Character.isISOControl(codePoint) || codePoint == 0x202A || codePoint == 0x202B
                || codePoint == 0x202D || codePoint == 0x202E || codePoint == 0x202C
                || codePoint == 0x2066 || codePoint == 0x2067 || codePoint == 0x2068 || codePoint == 0x2069;
    }

    private Object canonicalValue(AuthoredRuleNode node) {
        if (node instanceof AuthoredRuleNode.Predicate predicate) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("feature", predicate.feature());
            result.put("operator", predicate.operator().name());
            result.put("value", predicate.value());
            return result;
        }
        AuthoredRuleNode.Group group = (AuthoredRuleNode.Group) node;
        List<Object> clauses = group.clauses().stream().map(this::canonicalValue)
                .sorted(Comparator.comparing(this::write)).toList();
        return Map.of(group.mode() == AuthoredRuleNode.Mode.ALL ? "all" : "any", clauses);
    }

    private Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || result.putIfAbsent(key, entry.getValue()) != null) {
                throw invalid("Rule object keys must be unique strings");
            }
        }
        return result;
    }

    private String requireString(Object value, String name) {
        if (!(value instanceof String string) || string.isBlank()) {
            throw invalid("Predicate " + name + " must be a non-blank string");
        }
        return string;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Authored rule cannot be canonicalized", exception);
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record EncodedRule(AuthoredRuleNode node, String canonicalJson, String digest, int predicateCount) { }

    private static final class Counter {
        private int predicates;
    }
}
