package com.thaap.payments.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PublicApiErrorDescriptorTests {

    /**
     * A safe message that ends on a preposition, article, conjunction, or copula is
     * the fingerprint of a string concatenation whose dynamic tail was dropped. Such
     * a message is incomplete guidance and advertises that a runtime value was meant
     * to be appended.
     */
    private static final Pattern DANGLING_FUNCTION_WORD = Pattern.compile(
            "\\b(a|an|and|at|between|by|for|from|into|is|of|or|than|the|to|until|with)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * A bare noun directly after a preposition, such as {@code "from status"}, is the
     * same truncation fingerprint even though the sentence appears to end on a noun.
     * A complete message qualifies the noun, for example {@code "from its current status"}.
     */
    private static final Pattern DANGLING_BARE_NOUN = Pattern.compile(
            "\\b(from|for|to|at|in|into|with|than|until)\\s+"
                    + "(status|state|amount|currency|value|type|reason|code|id|key|role|limit)$",
            Pattern.CASE_INSENSITIVE);

    @Test
    void catalogueCodesAreUniqueBoundedAndCarryCompleteRecoverySemantics() {
        PublicApiErrorDescriptor[] descriptors = PublicApiErrorDescriptor.values();

        assertThat(descriptors).extracting(PublicApiErrorDescriptor::code)
                .doesNotHaveDuplicates()
                .allSatisfy(code -> assertThat(code).matches("[a-z][a-z0-9_]{2,63}"));
        assertThat(Arrays.asList(descriptors)).allSatisfy(descriptor -> {
            assertThat(descriptor.status().isError()).isTrue();
            assertThat(descriptor.safeMessage()).isNotBlank().doesNotContain("Exception", "SQL");
            assertThat(descriptor.recoveryAction()).matches("[a-z][a-z0-9_]{2,127}");
            if (descriptor.retryable()) {
                assertThat(descriptor.status().value()).isIn(429, 503, 504);
            }
        });
    }

    @Test
    void catalogueConstantsMirrorTheirStableCodeAndCarryCompleteSafeMessages() {
        assertThat(Arrays.asList(PublicApiErrorDescriptor.values())).allSatisfy(descriptor -> {
            assertThat(descriptor.name())
                    .as("constant must mirror the stable code so renames cannot silently diverge")
                    .isEqualTo(descriptor.code().toUpperCase(Locale.ROOT));
            assertThat(descriptor.code())
                    .as("codes name a domain failure class, not a sentence")
                    .doesNotMatch(".*_[0-9a-f]{10}$");
            assertThat(descriptor.code().split("_").length)
                    .as("code %s reads like prose rather than a failure class", descriptor.code())
                    .isLessThanOrEqualTo(7);

            String message = descriptor.safeMessage().strip();
            assertThat(DANGLING_FUNCTION_WORD.matcher(message).find())
                    .as("safe message for %s is truncated mid-sentence: '%s'", descriptor.code(), message)
                    .isFalse();
            assertThat(DANGLING_BARE_NOUN.matcher(message).find())
                    .as("safe message for %s drops an interpolated value: '%s'", descriptor.code(), message)
                    .isFalse();
            assertThat(message)
                    .as("safe message for %s must be reviewed prose, never an interpolation artifact",
                            descriptor.code())
                    .doesNotContain("null").doesNotContain("{").doesNotContain("%s");
        });
    }
}
