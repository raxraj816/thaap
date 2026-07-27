package com.thaap.payments.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;

class ReviewNoteContentValidatorTests {

    private final ReviewNoteContentValidator validator = new ReviewNoteContentValidator();

    @Test
    void normalizationAndExactLengthBoundariesAreDeterministic() {
        assertThat(validator.validate("  account holder confirmed device  ").normalizedContent())
                .isEqualTo("account holder confirmed device");
        assertThat(validator.validate("Ａnalyst observed mismatch").normalizedContent())
                .isEqualTo("Analyst observed mismatch");
        assertThat(validator.validate("x".repeat(2_000)).codePoints()).isEqualTo(2_000);
        assertThatThrownBy(() -> validator.validate("x".repeat(2_001)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("prohibitedContent")
    void prohibitedContentFamiliesFailBeforeEncryption(String name, String content) {
        org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertion =
                assertThatThrownBy(() -> validator.validate(content))
                        .isInstanceOf(ResponseStatusException.class);
        if (content != null) {
            assertion.hasMessageNotContaining(content);
        }
    }

    static Stream<Arguments> prohibitedContent() {
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("empty", "   "),
                Arguments.of("newline", "line one\nline two"),
                Arguments.of("bidi", "safe\u202Eunsafe"),
                Arguments.of("html", "<script>alert(1)</script>"),
                Arguments.of("pan", "card 4242 4242 4242 4242"),
                Arguments.of("fullwidth pan", "card ４２４２ ４２４２ ４２４２ ４２４２"),
                Arguments.of("cvv", "CVV: 123"),
                Arguments.of("openai key", "sk_live_abcdefghijklmnop"),
                Arguments.of("hyphenated openai key", "sk-abcdefghijklmnopqrstuvwxyz123456"),
                Arguments.of("project openai key", "sk-proj-abcdefghijklmnopqrstuvwxyz123456"),
                Arguments.of("labeled api key", "api_key=abcdefghijklmnopqrstuvwxyz123456"),
                Arguments.of("labeled client secret", "client secret: abcdefghijklmnopqrstuvwxyz123456"),
                Arguments.of("webhook secret", "whsec_abcdefghijklmnop"),
                Arguments.of("operator secret", "thaap_risk_0123456789abcdef0123456789abcdef"),
                Arguments.of("auth secret", "authc_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"),
                Arguments.of("bearer", "Bearer abcdefghijklmnopqrstuvwxyz.1234"),
                Arguments.of("url credentials", "https://alice:secret@example.test/path"));
    }
}
