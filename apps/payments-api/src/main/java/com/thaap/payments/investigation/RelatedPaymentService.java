package com.thaap.payments.investigation;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.observability.DecisionIntelligenceMetrics;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.persistence.JdbcTime;
import com.thaap.payments.projection.ActiveProjectionGeneration;
import com.thaap.payments.projection.ProjectionDefinitionStore;
import com.thaap.payments.projection.ProjectionGenerationUnavailableException;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.risk.RiskOperatorContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RelatedPaymentService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final ProjectionDefinitionStore projectionDefinitionStore;
    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceCursorCodec cursorCodec;
    private final Clock clock;
    private final DecisionIntelligenceMetrics metrics;

    public RelatedPaymentService(PaymentIntentRepository paymentIntentRepository,
            ProjectionDefinitionStore projectionDefinitionStore, JdbcTemplate jdbcTemplate,
            WorkspaceCursorCodec cursorCodec, Clock clock, DecisionIntelligenceMetrics metrics) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.projectionDefinitionStore = projectionDefinitionStore;
        this.jdbcTemplate = jdbcTemplate;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * The page declares a single {@code asOf} cutoff, so every statement behind it must observe one
     * snapshot. The anchor and customer queries pin their own ceiling by value, but the per-row
     * projection lookups do not, so without this transaction a rebuilding projection could report
     * two related payments from two different instants under one declared cutoff.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public RelatedPaymentPage related(RiskOperatorContext context, UUID seedPaymentId,
            Instant requestedFrom, Instant requestedTo, Integer requestedLimit, String cursor) {
        PaymentIntent seed = paymentIntentRepository.findByIdAndMerchantId(seedPaymentId,
                context.merchantId()).orElseThrow(() -> new PublicApiException(
                        PublicApiErrorDescriptor.PAYMENT_INVESTIGATION_NOT_FOUND));
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 50) {
            throw invalid("Related-payment page size must be between 1 and 50");
        }
        List<String> decoded = cursorCodec.decode("related-payments", context.merchantId(),
                seedPaymentId, cursor, 5);
        Instant from;
        Instant to;
        Instant asOf;
        Instant afterObserved;
        UUID afterPayment;
        if (decoded == null) {
            to = canonical(requestedTo == null ? clock.instant() : requestedTo);
            from = canonical(requestedFrom == null ? to.minus(7, ChronoUnit.DAYS) : requestedFrom);
            asOf = canonical(clock.instant());
            afterObserved = Instant.ofEpochSecond(253402300799L);
            afterPayment = new UUID(Long.MAX_VALUE, Long.MAX_VALUE);
        } else {
            try {
                from = Instant.parse(decoded.get(0));
                to = Instant.parse(decoded.get(1));
                asOf = Instant.parse(decoded.get(2));
                afterObserved = Instant.parse(decoded.get(3));
                afterPayment = UUID.fromString(decoded.get(4));
                if ((requestedFrom != null && !canonical(requestedFrom).equals(from))
                        || (requestedTo != null && !canonical(requestedTo).equals(to))) {
                    throw invalid("Related-payment cursor range does not match the request");
                }
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw invalid("Related-payment cursor is invalid");
            }
        }
        validateRange(from, to, asOf);
        Map<UUID, Aggregate> aggregates = anchorMatches(context.merchantId(), seedPaymentId,
                from, to, asOf);
        addCustomerMatches(aggregates, context.merchantId(), seed, from, to, asOf);
        List<Aggregate> sorted = aggregates.values().stream()
                .filter(aggregate -> aggregate.lastObservedAt.isBefore(afterObserved)
                        || (aggregate.lastObservedAt.equals(afterObserved)
                            && aggregate.paymentIntentId.compareTo(afterPayment) < 0))
                .sorted(Comparator.comparing((Aggregate aggregate) -> aggregate.lastObservedAt)
                        .reversed().thenComparing(Aggregate::paymentIntentId,
                                Comparator.reverseOrder()))
                .toList();
        List<Aggregate> page = sorted.subList(0, Math.min(limit + 1, sorted.size()));
        String nextCursor = null;
        if (page.size() > limit) {
            Aggregate last = page.get(limit - 1);
            nextCursor = cursorCodec.encode("related-payments", context.merchantId(),
                    seedPaymentId, List.of(from.toString(), to.toString(), asOf.toString(),
                            last.lastObservedAt.toString(), last.paymentIntentId.toString()));
            page = List.copyOf(page.subList(0, limit));
        }
        ActiveProjectionGeneration generation;
        try {
            generation = projectionDefinitionStore.requireActive(ProjectionNames.PAYMENT_RISK);
        } catch (ProjectionGenerationUnavailableException exception) {
            generation = null;
        }
        List<RelatedPayment> data = new ArrayList<>();
        for (Aggregate aggregate : page) {
            data.add(toView(context.merchantId(), generation, aggregate));
        }
        Coverage coverage = coverage(context.merchantId(), seedPaymentId);
        metrics.queryResult("RELATED_PAYMENTS", data.size(),
                coverage.state() == InvestigationSectionState.AVAILABLE ? "COMPLETE" : "PARTIAL");
        return new RelatedPaymentPage(seedPaymentId, from, to, asOf, data, nextCursor,
                coverage, "SAME_MERCHANT_SERVER_DERIVED");
    }

    private Map<UUID, Aggregate> anchorMatches(UUID merchantId, UUID seedPaymentId,
            Instant from, Instant to, Instant asOf) {
        Map<UUID, Aggregate> matches = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT candidate.payment_intent_id, candidate.relation_type,
                       MIN(candidate.observed_at), MAX(candidate.observed_at),
                       MIN(candidate.traffic_origin)
                FROM payment_investigation_relation_anchors seed
                JOIN payment_investigation_relation_anchors candidate
                  ON candidate.merchant_id = seed.merchant_id
                 AND candidate.relation_type = seed.relation_type
                 AND candidate.relation_identity_hmac = seed.relation_identity_hmac
                 AND candidate.identity_key_version = seed.identity_key_version
                WHERE seed.merchant_id = ? AND seed.payment_intent_id = ?
                  AND candidate.payment_intent_id <> ?
                  AND candidate.observed_at >= ? AND candidate.observed_at < ?
                  AND candidate.created_at <= ?
                GROUP BY candidate.payment_intent_id, candidate.relation_type
                """, rows -> {
                    UUID paymentId = rows.getObject(1, UUID.class);
                    Aggregate aggregate = matches.computeIfAbsent(paymentId, Aggregate::new);
                    aggregate.add(RelationReason.valueOf(rows.getString(2)),
                            JdbcTime.instant(rows, 3), JdbcTime.instant(rows, 4), rows.getString(5));
                }, merchantId, seedPaymentId, seedPaymentId, Timestamp.from(from),
                Timestamp.from(to), Timestamp.from(asOf));
        return matches;
    }

    private void addCustomerMatches(Map<UUID, Aggregate> matches, UUID merchantId,
            PaymentIntent seed, Instant from, Instant to, Instant asOf) {
        jdbcTemplate.query("""
                SELECT id, created_at, traffic_origin
                FROM payment_intents
                WHERE merchant_id = ? AND customer_id = ? AND id <> ?
                  AND created_at >= ? AND created_at < ? AND created_at <= ?
                """, rows -> {
                    UUID paymentId = rows.getObject(1, UUID.class);
                    Instant observedAt = JdbcTime.instant(rows, 2);
                    matches.computeIfAbsent(paymentId, Aggregate::new).add(
                            RelationReason.SAME_CUSTOMER, observedAt, observedAt,
                            rows.getString(3));
                }, merchantId, seed.getCustomerId(), seed.getId(), Timestamp.from(from),
                Timestamp.from(to), Timestamp.from(asOf));
    }

    private RelatedPayment toView(UUID merchantId, ActiveProjectionGeneration generation,
            Aggregate aggregate) {
        ProjectionSummary summary = null;
        if (generation != null) {
            List<ProjectionSummary> summaries = jdbcTemplate.query("""
                    SELECT lifecycle_status, fraud_action, risk_level, review_status,
                           label_value, intent_amount_minor, currency, traffic_origin,
                           coverage_status
                    FROM payment_risk_projections
                    WHERE merchant_id = ? AND projection_generation_id = ?
                      AND payment_intent_id = ?
                    """, (rows, number) -> new ProjectionSummary(rows.getString(1),
                            rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getString(5), nullableLong(rows.getObject(6)), rows.getString(7),
                            rows.getString(8), rows.getString(9)), merchantId,
                    generation.generationId(), aggregate.paymentIntentId);
            summary = summaries.isEmpty() ? null : summaries.getFirst();
        }
        return new RelatedPayment(aggregate.paymentIntentId,
                aggregate.reasons.stream().sorted().toList(), aggregate.reasons.size(),
                aggregate.firstObservedAt, aggregate.lastObservedAt,
                aggregate.trafficOrigins.stream().sorted().toList(), summary,
                summary == null ? InvestigationSectionState.PARTIAL
                        : "COMPLETE".equals(summary.coverageStatus())
                            ? InvestigationSectionState.AVAILABLE
                            : InvestigationSectionState.PARTIAL);
    }

    private Coverage coverage(UUID merchantId, UUID seedPaymentId) {
        Set<RelationReason> available = EnumSet.noneOf(RelationReason.class);
        jdbcTemplate.query("""
                SELECT DISTINCT relation_type
                FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND payment_intent_id = ?
                """, rows -> {
                    available.add(RelationReason.valueOf(rows.getString(1)));
                },
                merchantId, seedPaymentId);
        available.add(RelationReason.SAME_CUSTOMER);
        Set<RelationReason> unavailable = EnumSet.allOf(RelationReason.class);
        unavailable.removeAll(available);
        return new Coverage(unavailable.isEmpty() ? InvestigationSectionState.AVAILABLE
                : InvestigationSectionState.PARTIAL,
                available.stream().sorted().toList(), unavailable.stream().sorted().toList(),
                unavailable.isEmpty() ? null : "seed_identity_types_unavailable");
    }

    private void validateRange(Instant from, Instant to, Instant asOf) {
        if (!from.isBefore(to) || to.isAfter(asOf)
                || Duration.between(from, to).compareTo(Duration.ofDays(90)) > 0) {
            throw invalid("Related-payment range must be half-open, no more than 90 days, and not in the future");
        }
    }

    private Instant canonical(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private ResponseStatusException invalid(String message) {
        return new PublicApiException(PublicApiErrorDescriptor.WORKSPACE_CURSOR_INVALID);
    }

    public enum RelationReason {
        SAME_CUSTOMER,
        SAME_CARD_FINGERPRINT,
        SAME_EMAIL,
        SAME_PHONE,
        SAME_DEVICE,
        SAME_SESSION,
        SAME_IP
    }

    public record RelatedPaymentPage(UUID seedPaymentIntentId, Instant from, Instant to,
            Instant asOf, List<RelatedPayment> data, String nextCursor, Coverage coverage,
            String scope) {
    }

    public record RelatedPayment(UUID paymentIntentId, List<RelationReason> reasons,
            int matchedReasonCount, Instant firstObservedAt, Instant lastObservedAt,
            List<String> trafficOrigins, ProjectionSummary paymentAndDecision,
            InvestigationSectionState sectionState) {
    }

    public record ProjectionSummary(String lifecycleStatus, String fraudAction,
            String riskLevel, String reviewStatus, String labelValue, Long amountMinor,
            String currency, String trafficOrigin, String coverageStatus) {
    }

    public record Coverage(InvestigationSectionState state,
            List<RelationReason> availableReasons, List<RelationReason> unavailableReasons,
            String reasonCode) {
    }

    private static final class Aggregate {
        private final UUID paymentIntentId;
        private final Set<RelationReason> reasons = new LinkedHashSet<>();
        private final Set<String> trafficOrigins = new LinkedHashSet<>();
        private Instant firstObservedAt;
        private Instant lastObservedAt;

        private Aggregate(UUID paymentIntentId) {
            this.paymentIntentId = paymentIntentId;
        }

        private UUID paymentIntentId() {
            return paymentIntentId;
        }

        private void add(RelationReason reason, Instant first, Instant last, String origin) {
            reasons.add(reason);
            trafficOrigins.add(origin);
            firstObservedAt = firstObservedAt == null || first.isBefore(firstObservedAt)
                    ? first : firstObservedAt;
            lastObservedAt = lastObservedAt == null || last.isAfter(lastObservedAt)
                    ? last : lastObservedAt;
        }
    }
}
