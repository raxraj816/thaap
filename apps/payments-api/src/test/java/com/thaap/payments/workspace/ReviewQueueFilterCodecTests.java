package com.thaap.payments.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.review.ManualReviewCaseStatus;
import com.thaap.payments.review.ReviewStage;
import com.thaap.payments.tenant.TrafficOrigin;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReviewQueueFilterCodecTests {

    private final ReviewQueueFilterCodec codec = new ReviewQueueFilterCodec(
            new ObjectMapper().findAndRegisterModules());
    private final Instant now = Instant.parse("2026-07-25T12:00:00Z");

    @Test
    void canonicalMeaningIgnoresInputSetOrderAndRoundTripsExactly() {
        ReviewQueueFilter first = new ReviewQueueFilter(
                linked(ManualReviewCaseStatus.ASSIGNED, ManualReviewCaseStatus.OPEN),
                Set.of(ReviewStage.PRE_AUTHORIZATION), Set.of(FraudDecisionAction.REVIEW),
                linked(FraudDecisionReasonCode.ELEVATED_AMOUNT,
                        FraudDecisionReasonCode.VELOCITY_THRESHOLD_MATCH),
                Set.of(TrafficOrigin.MERCHANT_API), Set.of(UUID.fromString(
                        "00000000-0000-0000-0000-000000000001")),
                Set.of(ReviewSlaState.BREACHED), now.minusSeconds(3_600), now);
        ReviewQueueFilter second = new ReviewQueueFilter(
                linked(ManualReviewCaseStatus.OPEN, ManualReviewCaseStatus.ASSIGNED),
                Set.of(ReviewStage.PRE_AUTHORIZATION), Set.of(FraudDecisionAction.REVIEW),
                linked(FraudDecisionReasonCode.VELOCITY_THRESHOLD_MATCH,
                        FraudDecisionReasonCode.ELEVATED_AMOUNT),
                Set.of(TrafficOrigin.MERCHANT_API), first.assignedOperatorIds(),
                Set.of(ReviewSlaState.BREACHED), first.createdFrom(), first.createdTo());

        ReviewQueueFilterCodec.NormalizedFilter one = codec.normalize(first, now);
        ReviewQueueFilterCodec.NormalizedFilter two = codec.normalize(second, now);
        assertThat(one.canonicalJson()).isEqualTo(two.canonicalJson());
        assertThat(codec.decodeStored(ReviewQueueFilterCodec.SCHEMA_VERSION,
                one.canonicalJson(), now)).isEqualTo(one.filter());
        assertThat(codec.digest(one.canonicalJson(), ReviewQueueSort.CREATED_AT_DESC,
                codec.visibleColumnsJson(Set.of(ReviewQueueColumn.STATUS)))).hasSize(64);
    }

    @Test
    void intervalAndAggregateSetBoundariesFailClosed() {
        ReviewQueueFilter exactNinetyDays = empty(now.minusSeconds(90L * 86_400L), now);
        assertThat(codec.normalize(exactNinetyDays, now).filter().createdTo()).isEqualTo(now);
        assertThatThrownBy(() -> codec.normalize(
                empty(now.minusSeconds(90L * 86_400L).minusNanos(1_000), now), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> codec.normalize(empty(now, now), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> codec.normalize(empty(now.minusSeconds(1), now.plusSeconds(1)), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> codec.normalize(empty(now.minusSeconds(1), null), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> codec.normalize(empty(null, now), now))
                .isInstanceOf(ResponseStatusException.class);

        Set<UUID> exactMaximum = IntStream.range(0, 50)
                .mapToObj(index -> new UUID(0, index + 1L))
                .collect(java.util.stream.Collectors.toSet());
        ReviewQueueFilter maximum = new ReviewQueueFilter(Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), exactMaximum, Set.of(), null, null);
        assertThat(codec.normalize(maximum, now).filter().assignedOperatorIds()).hasSize(50);
        Set<UUID> tooMany = IntStream.range(0, 51)
                .mapToObj(index -> new UUID(0, index + 1L))
                .collect(java.util.stream.Collectors.toSet());
        ReviewQueueFilter excessive = new ReviewQueueFilter(Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), tooMany, Set.of(), null, null);
        assertThatThrownBy(() -> codec.normalize(excessive, now))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unsupportedSchemaUnknownFieldsAndDigestRepresentationsRequireUpgrade() {
        String canonical = codec.normalize(empty(null, null), now).canonicalJson();
        assertThatThrownBy(() -> codec.decodeStored("review_queue_filter_v0", canonical, now))
                .isInstanceOf(SavedSearchUpgradeRequiredException.class);
        String unknown = canonical.substring(0, canonical.length() - 1) + ",\"unknown\":true}";
        assertThatThrownBy(() -> codec.decodeStored(ReviewQueueFilterCodec.SCHEMA_VERSION,
                unknown, now)).isInstanceOf(SavedSearchUpgradeRequiredException.class);
        String reordered = canonical.replaceFirst("\\{", "{\"createdTo\":null,")
                .replaceFirst(",\"createdTo\":null", "");
        assertThatThrownBy(() -> codec.decodeStored(ReviewQueueFilterCodec.SCHEMA_VERSION,
                reordered, now)).isInstanceOf(SavedSearchUpgradeRequiredException.class);
    }

    private ReviewQueueFilter empty(Instant from, Instant to) {
        return new ReviewQueueFilter(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), from, to);
    }

    @SafeVarargs
    private static <T> Set<T> linked(T... values) {
        return new LinkedHashSet<>(java.util.List.of(values));
    }
}
