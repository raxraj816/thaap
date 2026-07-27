package com.thaap.payments.controls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuthoredRuleCodecTests {

    private final AuthoredRuleCodec codec = new AuthoredRuleCodec(new ObjectMapper().findAndRegisterModules());
    private final AuthoredRuleEvaluator evaluator = new AuthoredRuleEvaluator();

    @Test
    void canonicalBytesAreIndependentOfGroupAndSetOrder() {
        Map<String, Object> first = Map.of("all", List.of(
                Map.of("feature", "payment.currency", "operator", "IN", "value", List.of("USD", "INR")),
                Map.of("feature", "payment.amount_minor", "operator", "GTE", "value", 1000L)));
        Map<String, Object> permuted = Map.of("all", List.of(
                Map.of("feature", "payment.amount_minor", "operator", "GTE", "value", 1000),
                Map.of("feature", "payment.currency", "operator", "IN", "value", List.of("INR", "USD"))));

        AuthoredRuleCodec.EncodedRule left = codec.encode(first);
        AuthoredRuleCodec.EncodedRule right = codec.encode(permuted);

        assertThat(right.canonicalJson()).isEqualTo(left.canonicalJson());
        assertThat(right.digest()).isEqualTo(left.digest());
        assertThat(evaluator.matches(left.node(), Map.of(
                "payment.currency", "USD", "payment.amount_minor", 1000L))).isTrue();
        assertThat(evaluator.matches(left.node(), Map.of(
                "payment.currency", "EUR", "payment.amount_minor", 1000L))).isFalse();
    }

    @Test
    void parserRejectsUnknownKeysTypesOperatorsAndNonIntegralNumbers() {
        assertInvalid(Map.of("script", "return true"));
        assertInvalid(Map.of("feature", "buyer.email", "operator", "EQ", "value", "x@example.com"));
        assertInvalid(Map.of("feature", "payment.amount_minor", "operator", "REGEX", "value", 1L));
        assertInvalid(Map.of("feature", "payment.amount_minor", "operator", "GTE", "value", 1.5D));
        assertInvalid(Map.of("feature", "risk.context_present", "operator", "IN", "value", List.of(true)));
        assertInvalid(Map.of("feature", "payment.amount_minor", "operator", "GTE", "value", 0L));
        assertInvalid(Map.of("feature", "risk.bot_score_bps", "operator", "GTE", "value", 10_001L));
    }

    @Test
    void parserEnforcesDepthPredicateSetStringAndDuplicateSetLimits() {
        Map<String, Object> leaf = Map.of(
                "feature", "payment.currency", "operator", "EQ", "value", "USD");
        assertInvalid(Map.of("all", List.of(Map.of("all", List.of(Map.of("all", List.of(
                Map.of("all", List.of(leaf)))))))));
        assertInvalid(Map.of("any", java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> leaf).toList()));
        assertInvalid(Map.of("feature", "payment.currency", "operator", "IN",
                "value", java.util.stream.IntStream.range(0, 21).mapToObj(String::valueOf).toList()));
        assertInvalid(Map.of("feature", "payment.currency", "operator", "IN", "value", List.of("USD", "USD")));
        assertInvalid(Map.of("feature", "payment.currency", "operator", "EQ", "value", "\u202EUSD"));
        assertInvalid(Map.of("feature", "payment.currency", "operator", "EQ", "value", "x".repeat(129)));
        List<Map<String, Object>> expensive = IntStream.range(0, 32).mapToObj(predicate -> Map.of(
                "feature", "payment.currency", "operator", "IN", "value",
                IntStream.range(0, 20).mapToObj(value -> (predicate + "-" + value + "-")
                        + "x".repeat(90)).toList())).toList();
        assertInvalid(Map.of("all", expensive));
    }

    @Test
    void parserAcceptsExactDepthPredicateSetStringAndNumericBoundaries() {
        Map<String, Object> leaf = Map.of(
                "feature", "payment.currency", "operator", "EQ", "value", "x".repeat(128));
        AuthoredRuleCodec.EncodedRule depthThree = codec.encode(Map.of("all", List.of(
                Map.of("any", List.of(Map.of("all", List.of(leaf)))))));
        AuthoredRuleCodec.EncodedRule predicates = codec.encode(Map.of("any", IntStream.range(0, 32)
                .mapToObj(index -> Map.of("feature", "payment.amount_minor", "operator", "GTE",
                        "value", (long) index + 1L)).toList()));
        AuthoredRuleCodec.EncodedRule set = codec.encode(Map.of(
                "feature", "payment.currency", "operator", "IN",
                "value", IntStream.range(0, 20).mapToObj(String::valueOf).toList()));

        assertThat(depthThree.predicateCount()).isOne();
        assertThat(predicates.predicateCount()).isEqualTo(32);
        assertThat(set.predicateCount()).isOne();
        assertThat(codec.encode(Map.of("feature", "risk.bot_score_bps", "operator", "LTE",
                "value", 10_000L)).predicateCount()).isOne();
    }

    @Test
    void storedBytesRequireBothDigestAndCanonicalRepresentation() {
        AuthoredRuleCodec.EncodedRule encoded = codec.encode(Map.of(
                "feature", "payment.currency", "operator", "EQ", "value", "USD"));
        assertThat(codec.parseStored(encoded.canonicalJson(), encoded.digest()).node()).isEqualTo(encoded.node());
        assertThatThrownBy(() -> codec.parseStored(encoded.canonicalJson(), "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertInvalid(Map<String, Object> value) {
        assertThatThrownBy(() -> codec.encode(value)).isInstanceOf(ResponseStatusException.class);
    }
}
