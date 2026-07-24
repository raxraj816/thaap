package com.thaap.payments.fraudlist;

import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.riskcontext.RiskContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FraudListEvaluator {

    private final JdbcTemplate jdbcTemplate;
    private final MerchantRepository merchantRepository;
    private final CustomerRepository customerRepository;
    private final FraudListIdentityService identityService;
    private final Clock clock;
    private final FraudListMetrics metrics;

    public FraudListEvaluator(JdbcTemplate jdbcTemplate, MerchantRepository merchantRepository,
            CustomerRepository customerRepository,
            FraudListIdentityService identityService, Clock clock, FraudListMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.merchantRepository = merchantRepository;
        this.customerRepository = customerRepository;
        this.identityService = identityService;
        this.clock = clock;
        this.metrics = metrics;
    }

    public FraudListAssessment evaluate(PaymentIntent paymentIntent, PaymentMethod paymentMethod,
            RiskContext riskContext) {
        long startedNanos = System.nanoTime();
        Merchant merchant = merchantRepository.findByIdForRiskControlRead(paymentIntent.getMerchantId())
                .orElseThrow(() -> new IllegalStateException("PaymentIntent merchant lineage is missing"));
        if (!merchant.isActive()) {
            throw new IllegalStateException("PaymentIntent merchant is inactive during risk-control evaluation");
        }
        // PESSIMISTIC_READ is a shared PostgreSQL row lock. Confirmations for the same
        // merchant remain concurrent, while list mutations use the conflicting exclusive
        // merchant lock. Take the evaluation timestamp only after this ordering point.
        Instant evaluatedAt = clock.instant();
        Customer customer = customerRepository.findByIdAndMerchantId(
                        paymentIntent.getCustomerId(), paymentIntent.getMerchantId())
                .orElseThrow(() -> new IllegalStateException("PaymentIntent customer lineage is missing"));
        List<Row> rows = jdbcTemplate.query("""
                SELECT list.id, list.list_code, list.identity_type, list.generic_namespace,
                       version.id, version.version_number,
                       entry.id, entry.canonical_value, entry.value_hmac, entry.hmac_key_version,
                       entry.action, entry.reason_code, entry.created_at
                  FROM fraud_lists list
                  JOIN fraud_list_versions version
                    ON version.merchant_id = list.merchant_id
                   AND version.fraud_list_id = list.id
                  LEFT JOIN fraud_list_entries entry
                    ON entry.merchant_id = version.merchant_id
                   AND entry.fraud_list_version_id = version.id
                   AND entry.effective_from <= ?
                   AND (entry.effective_until IS NULL OR entry.effective_until > ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM fraud_list_lifecycle_events entry_event
                        WHERE entry_event.merchant_id = entry.merchant_id
                          AND entry_event.fraud_list_entry_id = entry.id
                          AND entry_event.event_type = 'ENTRY_DEACTIVATED'
                          AND entry_event.occurred_at <= ?)
                 WHERE list.merchant_id = ?
                   AND version.effective_from <= ?
                   AND EXISTS (
                       SELECT 1 FROM fraud_list_lifecycle_events activation
                        WHERE activation.merchant_id = version.merchant_id
                          AND activation.fraud_list_version_id = version.id
                          AND activation.event_type = 'VERSION_ACTIVATED'
                          AND activation.occurred_at <= ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM fraud_list_lifecycle_events deactivation
                        WHERE deactivation.merchant_id = version.merchant_id
                          AND deactivation.fraud_list_version_id = version.id
                          AND deactivation.event_type = 'VERSION_DEACTIVATED'
                          AND deactivation.occurred_at <= ?)
                 ORDER BY list.list_code, version.version_number, entry.created_at, entry.id
                """, (resultSet, rowNumber) -> row(resultSet), Timestamp.from(evaluatedAt),
                Timestamp.from(evaluatedAt), Timestamp.from(evaluatedAt), paymentIntent.getMerchantId(),
                Timestamp.from(evaluatedAt), Timestamp.from(evaluatedAt), Timestamp.from(evaluatedAt));

        Map<UUID, List<Row>> byVersion = new LinkedHashMap<>();
        for (Row row : rows) {
            byVersion.computeIfAbsent(row.versionId(), ignored -> new ArrayList<>()).add(row);
        }
        List<FraudListEvaluation> evaluations = new ArrayList<>();
        for (List<Row> versionRows : byVersion.values()) {
            Row definition = versionRows.getFirst();
            List<Row> matches = versionRows.stream().filter(Row::hasEntry)
                    .filter(row -> matches(row, paymentIntent, paymentMethod, customer, riskContext))
                    .sorted(Comparator.comparingInt((Row row) -> strength(row.action())).reversed()
                            .thenComparing(Row::createdAt).thenComparing(Row::entryId))
                    .toList();
            Row match = matches.isEmpty() ? null : matches.getFirst();
            evaluations.add(new FraudListEvaluation(definition.listId(), definition.versionId(),
                    definition.versionNumber(), definition.listCode(), definition.identityType(),
                    match == null ? null : match.entryId(), match == null ? null : match.action(),
                    match == null ? null : match.reasonCode(), evaluatedAt));
        }
        FraudListAssessment assessment = new FraudListAssessment(evaluations);
        for (FraudListEvaluation evaluation : evaluations) {
            metrics.recordEvaluation(evaluation);
        }
        metrics.recordEvaluationLatency(System.nanoTime() - startedNanos);
        return assessment;
    }

    private boolean matches(Row row, PaymentIntent intent, PaymentMethod method, Customer customer,
            RiskContext context) {
        if (row.canonicalValue() != null) {
            Set<String> candidates = identityService.canonicalCandidates(row.identityType(), method, context);
            return candidates.contains(row.canonicalValue());
        }
        List<String> candidates = identityService.candidateHmacs(intent.getMerchantId(), row.identityType(),
                row.namespace(), row.hmacKeyVersion(), intent, method, customer, context);
        return candidates.contains(row.valueHmac());
    }

    private int strength(FraudDecisionAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case REQUEST_3DS -> 1;
            case REVIEW -> 2;
            case BLOCK -> 3;
        };
    }

    private Row row(ResultSet rs) throws SQLException {
        UUID entryId = rs.getObject(7, UUID.class);
        Timestamp createdAt = rs.getTimestamp(13);
        return new Row(rs.getObject(1, UUID.class), rs.getString(2),
                FraudListIdentityType.valueOf(rs.getString(3)), namespace(rs.getString(4)),
                rs.getObject(5, UUID.class), rs.getInt(6), entryId, rs.getString(8), rs.getString(9),
                rs.getString(10), entryId == null ? null : FraudDecisionAction.valueOf(rs.getString(11)),
                entryId == null ? null : FraudListReasonCode.valueOf(rs.getString(12)),
                createdAt == null ? Instant.EPOCH : createdAt.toInstant());
    }

    private FraudListGenericNamespace namespace(String value) {
        return value == null ? null : FraudListGenericNamespace.valueOf(value);
    }

    private record Row(UUID listId, String listCode, FraudListIdentityType identityType,
            FraudListGenericNamespace namespace, UUID versionId, int versionNumber, UUID entryId,
            String canonicalValue, String valueHmac, String hmacKeyVersion, FraudDecisionAction action,
            FraudListReasonCode reasonCode, Instant createdAt) {
        boolean hasEntry() { return entryId != null; }
    }
}
