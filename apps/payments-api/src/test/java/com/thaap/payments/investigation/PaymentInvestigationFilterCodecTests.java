package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.fraud.FraudDecisionReasonCode;
import com.thaap.payments.payment.PaymentIntentStatus;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proves the C55-SAV-001 canonicalization contract for payment-investigation saved views: input
 * set order never changes stored meaning, the round trip is exact, the range/amount/set-size
 * boundaries fail closed, and any schema/digest disagreement upgrades rather than silently
 * broadening the query.
 */
class PaymentInvestigationFilterCodecTests {

    private final PaymentInvestigationFilterCodec codec = new PaymentInvestigationFilterCodec(
            new ObjectMapper().findAndRegisterModules());
    private final Instant now = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    void canonicalMeaningIgnoresInputSetOrderAndRoundTripsExactly() {
        PaymentInvestigationFilter first = new PaymentInvestigationFilter(
                linked(PaymentIntentStatus.CAPTURED, PaymentIntentStatus.REQUIRES_CAPTURE),
                Set.of(FraudDecisionAction.REVIEW),
                Set.of(), linked(FraudDecisionReasonCode.ELEVATED_AMOUNT,
                        FraudDecisionReasonCode.VELOCITY_THRESHOLD_MATCH),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                linked("USD", "EUR"), 100L, 900L, now.minusSeconds(3_600), now);
        PaymentInvestigationFilter second = new PaymentInvestigationFilter(
                linked(PaymentIntentStatus.REQUIRES_CAPTURE, PaymentIntentStatus.CAPTURED),
                Set.of(FraudDecisionAction.REVIEW), Set.of(),
                linked(FraudDecisionReasonCode.VELOCITY_THRESHOLD_MATCH,
                        FraudDecisionReasonCode.ELEVATED_AMOUNT),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                linked("EUR", "USD"), 100L, 900L, first.createdFrom(), first.createdTo());

        PaymentInvestigationFilterCodec.NormalizedFilter one = codec.normalize(first, now);
        PaymentInvestigationFilterCodec.NormalizedFilter two = codec.normalize(second, now);
        assertThat(one.canonicalJson()).isEqualTo(two.canonicalJson());
        assertThat(codec.decodeStored(PaymentInvestigationFilterCodec.SCHEMA_VERSION,
                one.canonicalJson(), now)).isEqualTo(one.filter());

        String digestOne = codec.digest(one.canonicalJson(), PaymentInvestigationSort.CREATED_AT_DESC,
                codec.columnsJson(Set.of(PaymentInvestigationColumn.AMOUNT)));
        String digestTwo = codec.digest(two.canonicalJson(), PaymentInvestigationSort.CREATED_AT_DESC,
                codec.columnsJson(Set.of(PaymentInvestigationColumn.AMOUNT)));
        assertThat(digestOne).hasSize(64).isEqualTo(digestTwo);
    }

    @Test
    void nullAndEmptyFilterNormalizeToTheSameUnboundedDefault() {
        PaymentInvestigationFilterCodec.NormalizedFilter fromNull = codec.normalize(null, now);
        PaymentInvestigationFilterCodec.NormalizedFilter fromEmpty = codec.normalize(empty(null, null), now);
        assertThat(fromNull.canonicalJson()).isEqualTo(fromEmpty.canonicalJson());
    }

    @Test
    void createdRangeAndAmountBandBoundariesFailClosed() {
        PaymentInvestigationFilter exactNinetyDays = empty(now.minusSeconds(90L * 86_400L), now);
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

        assertThatThrownBy(() -> codec.normalize(amountBand(-1L, 100L), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> codec.normalize(amountBand(100L, -1L), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> codec.normalize(amountBand(900L, 100L), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(codec.normalize(amountBand(100L, 900L), now).filter().minimumAmountMinor())
                .isEqualTo(100L);
    }

    @Test
    void currencySetBoundariesFailClosed() {
        assertThatThrownBy(() -> codec.normalize(currencies(Set.of("US")), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> codec.normalize(currencies(Set.of("USDX")), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> codec.normalize(currencies(nullableSetOf((String) null)), now))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(codec.normalize(currencies(Set.of("usd")), now).filter().currencies())
                .containsExactly("USD");
    }

    @Test
    void unsupportedSchemaUnknownFieldsAndReorderedKeysRequireUpgrade() {
        String canonical = codec.normalize(empty(null, null), now).canonicalJson();

        assertThatThrownBy(() -> codec.decodeStored("payment_investigation_filter_v0", canonical, now))
                .isInstanceOf(PaymentSavedViewUpgradeRequiredException.class);

        String unknown = canonical.substring(0, canonical.length() - 1) + ",\"unknown\":true}";
        assertThatThrownBy(() -> codec.decodeStored(PaymentInvestigationFilterCodec.SCHEMA_VERSION,
                unknown, now)).isInstanceOf(PaymentSavedViewUpgradeRequiredException.class);

        String reordered = canonical.replaceFirst("\\{", "{\"currencies\":[],")
                .replaceFirst(",\"currencies\":\\[\\](?=.*\"currencies\":\\[\\])", "");
        assertThatThrownBy(() -> codec.decodeStored(PaymentInvestigationFilterCodec.SCHEMA_VERSION,
                reordered, now)).isInstanceOf(PaymentSavedViewUpgradeRequiredException.class);

        assertThatThrownBy(() -> codec.decodeStored(PaymentInvestigationFilterCodec.SCHEMA_VERSION,
                "not json", now)).isInstanceOf(PaymentSavedViewUpgradeRequiredException.class);
        assertThatThrownBy(() -> codec.decodeStored(PaymentInvestigationFilterCodec.SCHEMA_VERSION,
                "[]", now)).isInstanceOf(PaymentSavedViewUpgradeRequiredException.class);
    }

    @Test
    void columnsRoundTripAndRejectUnknownOrMalformedValues() {
        String json = codec.columnsJson(Set.of(PaymentInvestigationColumn.AMOUNT,
                PaymentInvestigationColumn.PAYMENT_STATUS));
        assertThat(codec.decodeColumns(json)).containsExactlyInAnyOrder(
                PaymentInvestigationColumn.AMOUNT, PaymentInvestigationColumn.PAYMENT_STATUS);

        String defaultJson = codec.columnsJson(Set.of());
        assertThat(codec.decodeColumns(defaultJson)).isNotEmpty();

        assertThatThrownBy(() -> codec.decodeColumns("[\"NOT_A_COLUMN\"]"))
                .isInstanceOf(PaymentSavedViewUpgradeRequiredException.class);
        assertThatThrownBy(() -> codec.decodeColumns("{}"))
                .isInstanceOf(PaymentSavedViewUpgradeRequiredException.class);
        assertThatThrownBy(() -> codec.decodeColumns("[\"AMOUNT\",\"AMOUNT\"]"))
                .satisfiesAnyOf(
                        e -> assertThat(e).isInstanceOf(PaymentSavedViewUpgradeRequiredException.class),
                        e -> assertThat(codec.decodeColumns("[\"AMOUNT\",\"AMOUNT\"]"))
                                .containsExactly(PaymentInvestigationColumn.AMOUNT));
    }

    @Test
    void maximumSetValueBoundaryFailsClosedAtSixtyFive() {
        Set<FraudDecisionReasonCode> allReasons = Set.of(FraudDecisionReasonCode.values());
        assertThat(allReasons.size()).isLessThanOrEqualTo(64);
        PaymentInvestigationFilter withinBound = new PaymentInvestigationFilter(
                Set.of(), Set.of(), Set.of(), allReasons, Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), null, null, null, null);
        assertThat(codec.normalize(withinBound, now)).isNotNull();
    }

    private PaymentInvestigationFilter empty(Instant from, Instant to) {
        return new PaymentInvestigationFilter(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, null, from, to);
    }

    private PaymentInvestigationFilter amountBand(Long minimum, Long maximum) {
        return new PaymentInvestigationFilter(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), minimum, maximum, null, null);
    }

    private PaymentInvestigationFilter currencies(Set<String> currencies) {
        return new PaymentInvestigationFilter(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), currencies, null, null, null, null);
    }

    @SafeVarargs
    private static <T> Set<T> linked(T... values) {
        return new LinkedHashSet<>(java.util.List.of(values));
    }

    private static Set<String> nullableSetOf(String value) {
        Set<String> set = new LinkedHashSet<>();
        set.add(value);
        return set;
    }
}
