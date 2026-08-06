package com.thaap.payments.investigation;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.analytics.RiskAnalyticsService;
import com.thaap.payments.analytics.AnalyticsFreshness;
import com.thaap.payments.fraud.FeatureSnapshot;
import com.thaap.payments.fraud.FeatureSnapshotRepository;
import com.thaap.payments.fraud.FeatureValue;
import com.thaap.payments.fraud.FeatureValueRepository;
import com.thaap.payments.fraud.FraudDecision;
import com.thaap.payments.fraud.FraudDecisionRepository;
import com.thaap.payments.fraud.RuleExecutionResult;
import com.thaap.payments.fraud.RuleExecutionResultRepository;
import com.thaap.payments.observability.DecisionIntelligenceMetrics;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.persistence.JdbcTime;
import com.thaap.payments.projection.ProjectionGenerationUnavailableException;
import com.thaap.payments.review.ManualReviewCase;
import com.thaap.payments.review.ManualReviewCaseRepository;
import com.thaap.payments.review.ReviewStage;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.risk.RiskOperatorRole;
import com.thaap.payments.velocity.VelocityDimension;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentInvestigationService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final FraudDecisionRepository fraudDecisionRepository;
    private final FeatureSnapshotRepository featureSnapshotRepository;
    private final FeatureValueRepository featureValueRepository;
    private final RuleExecutionResultRepository ruleExecutionResultRepository;
    private final ManualReviewCaseRepository manualReviewCaseRepository;
    private final RiskAnalyticsService analyticsService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final DecisionIntelligenceMetrics metrics;

    public PaymentInvestigationService(PaymentIntentRepository paymentIntentRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            FraudDecisionRepository fraudDecisionRepository,
            FeatureSnapshotRepository featureSnapshotRepository,
            FeatureValueRepository featureValueRepository,
            RuleExecutionResultRepository ruleExecutionResultRepository,
            ManualReviewCaseRepository manualReviewCaseRepository,
            RiskAnalyticsService analyticsService, JdbcTemplate jdbcTemplate, Clock clock,
            DecisionIntelligenceMetrics metrics) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.fraudDecisionRepository = fraudDecisionRepository;
        this.featureSnapshotRepository = featureSnapshotRepository;
        this.featureValueRepository = featureValueRepository;
        this.ruleExecutionResultRepository = ruleExecutionResultRepository;
        this.manualReviewCaseRepository = manualReviewCaseRepository;
        this.analyticsService = analyticsService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public InvestigationDetail detail(RiskOperatorContext context, UUID paymentIntentId) {
        PaymentIntent payment = paymentIntentRepository.findByIdAndMerchantId(
                paymentIntentId, context.merchantId()).orElseThrow(() -> notFound());
        Instant sourceAsOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<PaymentAttempt> attempts = paymentAttemptRepository
                .findByMerchantIdAndPaymentIntentIdOrderByAttemptNumberAsc(
                        context.merchantId(), paymentIntentId);
        List<AttemptView> attemptViews = attempts.stream().map(this::attemptView).toList();
        PaymentAttempt latestAttempt = attempts.isEmpty() ? null : attempts.getLast();
        FraudDecision latestDecision = latestAttempt == null ? null
                : fraudDecisionRepository.findByMerchantIdAndPaymentAttemptId(
                        context.merchantId(), latestAttempt.getId()).orElse(null);
        DecisionExplanation explanation = latestDecision == null ? null
                : decisionExplanation(context, latestDecision);
        List<ReviewView> reviews = reviews(context, paymentIntentId);
        RiskAnalyticsService.PaymentDetail analytics = analytics(context, paymentIntentId);
        MoneyAndDisputes money = moneyAndDisputes(context.merchantId(), paymentIntentId,
                payment.getCurrency());
        WebhookSummary webhookSummary = webhookSummary(context.merchantId(), paymentIntentId);
        long maximumStreamVersion = maximumStreamVersion(context.merchantId(), paymentIntentId);
        Map<String, SectionStatus> sectionStatuses = sectionStatuses(attemptViews, explanation,
                reviews, analytics, money, webhookSummary);
        recordSectionMetrics(sectionStatuses);
        return new InvestigationDetail(
                new InvestigationIdentity(payment.getId(),
                        latestAttempt == null ? null : latestAttempt.getId(),
                        latestDecision == null ? null : latestDecision.getId(),
                        reviews.isEmpty() ? null : reviews.getLast().id(), payment.getTrafficOrigin().name()),
                new SourceSnapshot(sourceAsOf, maximumStreamVersion, "REPEATABLE_READ", "SOURCE_DATABASE"),
                analytics == null ? null : new AnalyticsContext(analytics.freshness()),
                new PaymentLifecycle(payment.getStatus().name(), payment.getAmount(), payment.getCurrency(),
                        payment.getCaptureMethod().name(), payment.getCreatedAt(), payment.getUpdatedAt(),
                        attemptViews),
                explanation,
                new ReviewAndLabels(reviews, labels(context.merchantId(), paymentIntentId)),
                money,
                webhookSummary,
                availableActions(context, reviews),
                new RelatedLinksSummary("/risk/workspace/payments/" + paymentIntentId + "/related",
                        "SERVER_DERIVED_SAME_MERCHANT_ONLY"),
                sectionStatuses);
    }

    private void recordSectionMetrics(Map<String, SectionStatus> statuses) {
        Map<String, String> names = Map.of(
                "paymentLifecycle", "PAYMENT_LIFECYCLE",
                "decisionExplanation", "DECISION_EXPLANATION",
                "reviewAndLabels", "REVIEW_AND_LABELS",
                "moneyAndDisputes", "MONEY_AND_DISPUTES",
                "webhookSummary", "WEBHOOK_SUMMARY",
                "analyticsContext", "ANALYTICS_CONTEXT");
        statuses.forEach((name, status) -> metrics.section(names.get(name), status.state().name()));
    }

    /**
     * The projection is an optional context section. A payment with no projection row, or an
     * outage that leaves no servable generation, degrades this one section; it must never take
     * down the authoritative source detail an analyst is trying to read during an incident.
     */
    private RiskAnalyticsService.PaymentDetail analytics(RiskOperatorContext context,
            UUID paymentIntentId) {
        try {
            return analyticsService.paymentDetail(context, paymentIntentId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == 404) {
                return null;
            }
            throw exception;
        } catch (ProjectionGenerationUnavailableException exception) {
            return null;
        }
    }

    private DecisionExplanation decisionExplanation(RiskOperatorContext context,
            FraudDecision decision) {
        FeatureSnapshot snapshot = featureSnapshotRepository
                .findByFraudDecisionIdAndMerchantId(decision.getId(), context.merchantId())
                .orElse(null);
        List<FeatureValueView> featureValues = featureValueRepository
                .findPointInTimeForDecision(context.merchantId(), decision.getId()).stream()
                .map(this::featureValue).toList();
        List<ControlEvaluation> controls = new ArrayList<>();
        for (RuleExecutionResult result : ruleExecutionResultRepository
                .findByFraudDecisionIdAndMerchantIdOrderByPriorityAscRuleCodeAsc(
                        decision.getId(), context.merchantId())) {
            controls.add(new ControlEvaluation("SYSTEM_RULE", result.getRuleCode(),
                    result.getRuleVersion(), result.getPriority(), "AVAILABLE",
                    result.getEvaluationOutcome().name(), result.isMatched(),
                    result.getAction().name(), result.getReasonCode().name(),
                    result.getLatencyUs(), false));
        }
        controls.addAll(additionalControls(context.merchantId(), decision));
        controls.sort(Comparator.comparingInt((ControlEvaluation evaluation) ->
                        controlFamilyRank(evaluation.family()))
                .thenComparing(evaluation -> evaluation.priority() == null
                        ? Integer.MAX_VALUE : evaluation.priority())
                .thenComparing(ControlEvaluation::code));
        int winnerIndex = winnerIndex(controls, decision.getAction().name());
        if (winnerIndex >= 0) {
            ControlEvaluation winner = controls.get(winnerIndex);
            controls.set(winnerIndex, new ControlEvaluation(winner.family(), winner.code(),
                    winner.version(), winner.priority(), winner.availability(), winner.outcome(),
                    winner.matched(), winner.proposedAction(), winner.reason(), winner.latencyMicros(), true));
        }
        String winningControl = winnerIndex >= 0 ? controls.get(winnerIndex).code()
                : "ALLOW".equals(decision.getAction().name())
                        ? "DEFAULT_ALLOW" : "UNRESOLVED_CONTROL_LINEAGE";
        String precedenceText = "UNRESOLVED_CONTROL_LINEAGE".equals(winningControl)
                ? "No persisted control evaluation exactly reconciles to the final action; the evidence is incomplete."
                : "The strongest action wins; equal-strength ties retain system, velocity, fraud-list, merchant-policy, then authored-rule order. Velocity unavailability deliberately proposes REVIEW under the fail-closed policy.";
        return new DecisionExplanation(toBasisPoints(decision.getRiskScore()),
                decision.getRiskLevel().name(), decision.getAction().name(),
                decision.getReasonCodes().stream().map(Enum::name).toList(),
                decision.getDecisionStartedAt(), decision.getDecisionCompletedAt(),
                decision.getLatencyMs(), snapshot == null ? null : snapshot.getFeatureVersion(),
                snapshot == null ? null : snapshot.getComputedAt(),
                snapshot == null ? null : snapshot.getOldestFeatureTimestamp(),
                snapshot == null ? decision.getMaxFeatureAgeMs() : snapshot.getMaxFeatureAgeMs(),
                snapshot == null ? decision.getFeatureFreshnessStatus().name()
                        : snapshot.getFreshnessStatus().name(),
                snapshot == null ? List.of() : snapshot.getMissingFeatureNames(),
                snapshot == null ? List.of() : snapshot.getStaleFeatureNames(),
                featureValues, List.copyOf(controls), "NOT_CONFIGURED",
                new PrecedenceExplanation(winningControl, decision.getAction().name(),
                        precedenceText));
    }

    private List<ControlEvaluation> additionalControls(UUID merchantId, FraudDecision decision) {
        List<ControlEvaluation> controls = new ArrayList<>();
        controls.addAll(jdbcTemplate.query("""
                SELECT dimension, script_version, availability, other_attempt_count, failure_cause
                FROM velocity_observations
                WHERE merchant_id = ? AND fraud_decision_id = ?
                ORDER BY dimension
                """, (rows, number) -> {
                    VelocityDimension dimension = VelocityDimension.valueOf(rows.getString(1));
                    String availability = rows.getString(3);
                    Long otherAttemptCount = rows.getObject(4, Long.class);
                    boolean matched = "AVAILABLE".equals(availability)
                            && otherAttemptCount != null
                            && otherAttemptCount >= dimension.reviewThreshold();
                    boolean failClosed = "UNAVAILABLE".equals(availability);
                    return new ControlEvaluation("VELOCITY", dimension.name(), rows.getString(2),
                            null, availability, failClosed ? "FAIL_CLOSED"
                                    : matched ? "MATCH" : "NO_MATCH",
                            matched, matched || failClosed ? "REVIEW" : null,
                            failClosed ? "VELOCITY_UNAVAILABLE"
                                    : matched ? "VELOCITY_THRESHOLD_MATCH" : null,
                            null, false);
                }, merchantId, decision.getId()));
        controls.addAll(jdbcTemplate.query("""
                SELECT identity_type, fraud_list_version_id, matched, candidate_action, reason_code
                FROM fraud_list_evaluations
                WHERE merchant_id = ? AND fraud_decision_id = ?
                ORDER BY identity_type, fraud_list_version_id
                """, (rows, number) -> new ControlEvaluation("FRAUD_LIST",
                        "FRAUD_LIST_" + rows.getString(1), rows.getString(2), null, "AVAILABLE",
                        rows.getBoolean(3) ? "MATCH" : "NO_MATCH", rows.getBoolean(3),
                        rows.getString(4), rows.getString(5), null, false), merchantId, decision.getId()));
        controls.addAll(jdbcTemplate.query("""
                SELECT control_type, feature_code, COALESCE(CAST(policy_version_id AS VARCHAR),
                       CAST(profile_version_id AS VARCHAR)), priority, configuration_status,
                       matched, candidate_action, reason_code
                FROM risk_control_evaluations
                WHERE merchant_id = ? AND fraud_decision_id = ?
                ORDER BY control_type, feature_code, policy_threshold_id
                """, (rows, number) -> new ControlEvaluation("MERCHANT_POLICY_" + rows.getString(1),
                        rows.getString(2), rows.getString(3), nullableInteger(rows, 4),
                        rows.getString(5), rows.getBoolean(6) ? "MATCH" : "NO_MATCH",
                        rows.getBoolean(6), rows.getString(7), rows.getString(8), null, false),
                merchantId, decision.getId()));
        controls.addAll(jdbcTemplate.query("""
                SELECT rule.rule_code, CAST(rule.version_number AS VARCHAR),
                       evaluation.matched, evaluation.candidate_action,
                       evaluation.exception_disposition, evaluation.effective_action
                FROM authored_rule_evaluations evaluation
                JOIN authored_rule_versions rule
                  ON rule.merchant_id = evaluation.merchant_id
                 AND rule.id = evaluation.rule_version_id
                WHERE evaluation.merchant_id = ? AND evaluation.fraud_decision_id = ?
                ORDER BY rule.rule_code, rule.version_number
                """, (rows, number) -> new ControlEvaluation("AUTHORED_RULE",
                        rows.getString(1), rows.getString(2), null, "AVAILABLE",
                        rows.getBoolean(3) ? "MATCH" : "NO_MATCH", rows.getBoolean(3),
                        rows.getString(6), rows.getString(5), null, false), merchantId, decision.getId()));
        return controls;
    }

    private int winnerIndex(List<ControlEvaluation> controls, String action) {
        for (int index = 0; index < controls.size(); index++) {
            ControlEvaluation evaluation = controls.get(index);
            boolean appliedVelocityFallback = "VELOCITY".equals(evaluation.family())
                    && "FAIL_CLOSED".equals(evaluation.outcome());
            if ((evaluation.matched() || appliedVelocityFallback)
                    && action.equals(evaluation.proposedAction())) {
                return index;
            }
        }
        return -1;
    }

    private int controlFamilyRank(String family) {
        if ("SYSTEM_RULE".equals(family)) return 0;
        if ("VELOCITY".equals(family)) return 1;
        if ("FRAUD_LIST".equals(family)) return 2;
        if (family.startsWith("MERCHANT_POLICY_")) return 3;
        if ("AUTHORED_RULE".equals(family)) return 4;
        throw new IllegalStateException("Unknown persisted control family: " + family);
    }

    private List<ReviewView> reviews(RiskOperatorContext context, UUID paymentIntentId) {
        List<ReviewView> reviews = new ArrayList<>();
        for (ReviewStage stage : ReviewStage.values()) {
            manualReviewCaseRepository.findByPaymentIntentIdAndMerchantIdAndReviewStage(
                    paymentIntentId, context.merchantId(), stage).ifPresent(review ->
                            reviews.add(reviewView(review)));
        }
        reviews.sort(Comparator.comparing(ReviewView::createdAt).thenComparing(ReviewView::id));
        return List.copyOf(reviews);
    }

    private ReviewView reviewView(ManualReviewCase review) {
        return new ReviewView(review.getId(), review.getPaymentAttemptId(),
                review.getReviewStage().name(), review.getStatus().name(),
                review.getDecisionAction().name(),
                review.getReasonCodes().stream().map(Enum::name).toList(),
                review.getAssignedOperatorId(), review.getAssignmentVersion(),
                review.getCreatedAt(), review.getUpdatedAt(), review.getResolvedAt());
    }

    private List<LabelView> labels(UUID merchantId, UUID paymentIntentId) {
        return jdbcTemplate.query("""
                SELECT label.id, label.label_value, label.fraud_type, label.source,
                       label.evidence_occurred_at, label.available_at
                FROM fraud_labels label
                WHERE label.merchant_id = ? AND label.payment_intent_id = ?
                ORDER BY label.available_at, label.id
                """, (rows, number) -> new LabelView(rows.getObject(1, UUID.class),
                        rows.getString(2), rows.getString(3), rows.getString(4),
                        JdbcTime.instant(rows, 5), JdbcTime.instant(rows, 6)),
                merchantId, paymentIntentId);
    }

    private MoneyAndDisputes moneyAndDisputes(UUID merchantId, UUID paymentIntentId,
            String currency) {
        Long captured = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM ledger_entries
                WHERE merchant_id = ? AND payment_intent_id = ?
                  AND account = 'MERCHANT_PAYABLE' AND direction = 'CREDIT'
                  AND source_type = 'PAYMENT_INTENT_CAPTURE'
                """, Long.class, merchantId, paymentIntentId);
        Long refunded = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM refunds
                WHERE merchant_id = ? AND payment_intent_id = ? AND status = 'RECORDED'
                """, Long.class, merchantId, paymentIntentId);
        List<DisputeView> disputes = jdbcTemplate.query("""
                SELECT id, amount, allocated_amount, currency, reason_code, status,
                       evidence_occurred_at, available_at
                FROM chargebacks
                WHERE merchant_id = ? AND payment_intent_id = ?
                ORDER BY available_at, id
                """, (rows, number) -> new DisputeView(rows.getObject(1, UUID.class),
                        rows.getLong(2), rows.getLong(3), rows.getString(4), rows.getString(5),
                        rows.getString(6), JdbcTime.instant(rows, 7), JdbcTime.instant(rows, 8)),
                merchantId, paymentIntentId);
        long disputed = disputes.stream().mapToLong(DisputeView::allocatedAmountMinor).sum();
        Long debit = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM ledger_entries
                WHERE merchant_id = ? AND payment_intent_id = ? AND direction = 'DEBIT'
                """, Long.class, merchantId, paymentIntentId);
        Long credit = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM ledger_entries
                WHERE merchant_id = ? AND payment_intent_id = ? AND direction = 'CREDIT'
                """, Long.class, merchantId, paymentIntentId);
        return new MoneyAndDisputes(currency, value(captured), value(refunded), disputed,
                value(debit), value(credit), value(debit) == value(credit), disputes);
    }

    private WebhookSummary webhookSummary(UUID merchantId, UUID paymentIntentId) {
        List<WebhookStatusCount> counts = jdbcTemplate.query("""
                SELECT status, COUNT(*), COALESCE(SUM(attempt_count), 0),
                       MAX(last_attempt_at), MAX(delivered_at)
                FROM webhook_deliveries
                WHERE merchant_id = ? AND resource_id = ?
                GROUP BY status ORDER BY status
                """, (rows, number) -> new WebhookStatusCount(rows.getString(1), rows.getLong(2),
                        rows.getLong(3), JdbcTime.instant(rows, 4), JdbcTime.instant(rows, 5)),
                merchantId, paymentIntentId);
        return new WebhookSummary(counts.stream().mapToLong(WebhookStatusCount::deliveryCount).sum(),
                counts);
    }

    private long maximumStreamVersion(UUID merchantId, UUID paymentIntentId) {
        Long version = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(stream_version), 0) FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                """, Long.class, merchantId, paymentIntentId);
        return value(version);
    }

    private Map<String, SectionStatus> sectionStatuses(List<AttemptView> attempts,
            DecisionExplanation explanation, List<ReviewView> reviews,
            RiskAnalyticsService.PaymentDetail analytics, MoneyAndDisputes money,
            WebhookSummary webhooks) {
        Map<String, SectionStatus> statuses = new LinkedHashMap<>();
        statuses.put("paymentLifecycle", status("SOURCE_DATABASE",
                InvestigationSectionState.AVAILABLE, null));
        statuses.put("decisionExplanation", status("DECISION_TIME_SOURCE",
                explanation == null ? InvestigationSectionState.NOT_APPLICABLE
                        : InvestigationSectionState.AVAILABLE, null));
        statuses.put("reviewAndLabels", status("REVIEW_SOURCE",
                reviews.isEmpty() ? InvestigationSectionState.EMPTY : InvestigationSectionState.AVAILABLE, null));
        statuses.put("moneyAndDisputes", status("LEDGER_AND_DISPUTE_SOURCE",
                money.disputes().isEmpty() && money.capturedAmountMinor() == 0
                        ? InvestigationSectionState.EMPTY : InvestigationSectionState.AVAILABLE, null));
        statuses.put("webhookSummary", status("WEBHOOK_TRANSPORT_SOURCE",
                webhooks.deliveryCount() == 0 ? InvestigationSectionState.EMPTY
                        : InvestigationSectionState.AVAILABLE, null));
        if (analytics == null) {
            statuses.put("analyticsContext", status("ACTIVE_PAYMENT_RISK_PROJECTION",
                    InvestigationSectionState.UNAVAILABLE, "active_projection_unavailable"));
        } else {
            String freshness = analytics.freshness().state().name();
            InvestigationSectionState state = switch (freshness) {
                case "FRESH" -> InvestigationSectionState.AVAILABLE;
                case "EMPTY" -> InvestigationSectionState.EMPTY;
                case "PARTIAL" -> InvestigationSectionState.PARTIAL;
                case "LAGGING", "STALE" -> InvestigationSectionState.STALE;
                default -> InvestigationSectionState.UNAVAILABLE;
            };
            statuses.put("analyticsContext", status("ACTIVE_PAYMENT_RISK_PROJECTION", state,
                    state == InvestigationSectionState.AVAILABLE ? null : freshness.toLowerCase()));
        }
        return Map.copyOf(statuses);
    }

    private SectionStatus status(String authority, InvestigationSectionState state,
            String reasonCode) {
        return new SectionStatus(authority, state, reasonCode);
    }

    private List<ActionEligibility> availableActions(RiskOperatorContext context,
            List<ReviewView> reviews) {
        List<ActionEligibility> actions = new ArrayList<>();
        boolean mutable = context.role() != RiskOperatorRole.VIEWER;
        boolean manager = context.role() == RiskOperatorRole.RISK_MANAGER;
        ReviewView openReview = reviews.stream()
                .filter(review -> List.of("OPEN", "ASSIGNED").contains(review.status()))
                .findFirst().orElse(null);
        boolean unassigned = openReview != null && "OPEN".equals(openReview.status());
        boolean ownsAssignment = openReview != null && "ASSIGNED".equals(openReview.status())
                && (manager || context.operatorId().equals(openReview.assignedOperatorId()));
        actions.add(eligibility("ASSIGN_REVIEW", mutable && openReview != null
                && (unassigned || manager), mutable, openReview, "review_already_assigned"));
        actions.add(eligibility("RELEASE_REVIEW", mutable && ownsAssignment,
                mutable, openReview, "assignment_owned_by_another_operator"));
        actions.add(eligibility("RESOLVE_REVIEW", mutable && ownsAssignment,
                mutable, openReview, "review_must_be_assigned_to_actor"));
        return List.copyOf(actions);
    }

    private ActionEligibility eligibility(String action, boolean eligible, boolean mutable,
            ReviewView review, String ineligibleReason) {
        String reason = eligible ? null : !mutable ? "viewer_read_only"
                : review == null ? "no_open_review" : ineligibleReason;
        return new ActionEligibility(action, eligible, reason);
    }

    private AttemptView attemptView(PaymentAttempt attempt) {
        return new AttemptView(attempt.getId(), attempt.getAttemptNumber(), attempt.getStatus().name(),
                attempt.getFraudDecisionId(), attempt.getThreeDsStatus() == null ? null
                        : attempt.getThreeDsStatus().name(), attempt.getTrafficOrigin().name(),
                attempt.getCreatedAt(), attempt.getUpdatedAt());
    }

    private FeatureValueView featureValue(FeatureValue value) {
        return new FeatureValueView(value.getFeatureCode(), value.getFeatureVersion(),
                value.getValueType().name(), value.typedValue(), value.getObservedAt(),
                value.getComputedAt());
    }

    private int toBasisPoints(BigDecimal score) {
        return score.multiply(BigDecimal.valueOf(10_000))
                .setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private Integer nullableInteger(ResultSet rows, int column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private ResponseStatusException notFound() {
        return new PublicApiException(PublicApiErrorDescriptor.PAYMENT_INVESTIGATION_NOT_FOUND);
    }

    public record InvestigationDetail(InvestigationIdentity identity,
            SourceSnapshot sourceSnapshot, AnalyticsContext analyticsContext,
            PaymentLifecycle paymentLifecycle, DecisionExplanation decisionExplanation,
            ReviewAndLabels reviewAndLabels, MoneyAndDisputes moneyAndDisputes,
            WebhookSummary webhookSummary, List<ActionEligibility> availableActions,
            RelatedLinksSummary relatedLinksSummary,
            Map<String, SectionStatus> sectionStatuses) {
    }

    public record InvestigationIdentity(UUID paymentIntentId, UUID latestPaymentAttemptId,
            UUID latestFraudDecisionId, UUID latestReviewCaseId, String trafficOrigin) {
    }

    public record SourceSnapshot(Instant sourceAsOf, long maximumPaymentStreamVersion,
            String consistency, String authority) {
    }

    public record AnalyticsContext(AnalyticsFreshness freshness) {
    }

    public record PaymentLifecycle(String status, long amountMinor, String currency,
            String captureMethod, Instant createdAt, Instant updatedAt,
            List<AttemptView> attempts) {
    }

    public record AttemptView(UUID id, int attemptNumber, String status, UUID fraudDecisionId,
            String threeDsStatus, String trafficOrigin, Instant createdAt, Instant updatedAt) {
    }

    public record DecisionExplanation(int riskScoreBps, String riskLevel, String finalAction,
            List<String> reasonCodes, Instant decisionStartedAt, Instant decisionCompletedAt,
            long latencyMs, String featureCatalogueVersion, Instant featuresComputedAt,
            Instant oldestFeatureObservedAt, long maximumFeatureAgeMs, String featureFreshness,
            List<String> missingFeatureNames, List<String> staleFeatureNames,
            List<FeatureValueView> featureValues, List<ControlEvaluation> controlEvaluations,
            String modelState, PrecedenceExplanation precedence) {
    }

    public record FeatureValueView(String code, String version, String valueType,
            Object value, Instant observedAt, Instant computedAt) {
    }

    public record ControlEvaluation(String family, String code, String version,
            Integer priority, String availability, String outcome, boolean matched,
            String proposedAction, String reason, Long latencyMicros,
            boolean contributedToWinningAction) {
    }

    public record PrecedenceExplanation(String winningControl, String winningAction,
            String explanation) {
    }

    public record ReviewAndLabels(List<ReviewView> reviews, List<LabelView> labels) {
    }

    public record ReviewView(UUID id, UUID paymentAttemptId, String reviewStage, String status,
            String decisionAction, List<String> reasonCodes, UUID assignedOperatorId,
            long assignmentVersion, Instant createdAt, Instant updatedAt, Instant resolvedAt) {
    }

    public record LabelView(UUID id, String value, String fraudType, String source,
            Instant evidenceOccurredAt, Instant availableAt) {
    }

    public record MoneyAndDisputes(String currency, long capturedAmountMinor,
            long refundedAmountMinor, long disputedAmountMinor, long ledgerDebitMinor,
            long ledgerCreditMinor, boolean ledgerBalanced, List<DisputeView> disputes) {
    }

    public record DisputeView(UUID id, long amountMinor, long allocatedAmountMinor,
            String currency, String reasonCode, String status, Instant evidenceOccurredAt,
            Instant availableAt) {
    }

    public record WebhookSummary(long deliveryCount, List<WebhookStatusCount> statuses) {
    }

    public record WebhookStatusCount(String status, long deliveryCount, long attemptCount,
            Instant lastAttemptAt, Instant lastDeliveredAt) {
    }

    public record ActionEligibility(String action, boolean eligible, String reasonCode) {
    }

    public record RelatedLinksSummary(String route, String scope) {
    }

    public record SectionStatus(String authority, InvestigationSectionState state,
            String reasonCode) {
    }
}
