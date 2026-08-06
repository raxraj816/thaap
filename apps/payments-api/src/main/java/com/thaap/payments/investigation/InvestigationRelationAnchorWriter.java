package com.thaap.payments.investigation;

import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentattempt.PaymentAttemptRepository;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.paymentmethod.PaymentMethodRepository;
import com.thaap.payments.riskcontext.RiskContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class InvestigationRelationAnchorWriter {

    private static final String INSERT = """
            INSERT INTO payment_investigation_relation_anchors
                (id, merchant_id, payment_intent_id, payment_attempt_id,
                 relation_type, relation_identity_hmac, identity_key_version,
                 observed_at, traffic_origin, source_type, source_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CustomerRepository customerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public InvestigationRelationAnchorWriter(PaymentAttemptRepository paymentAttemptRepository,
            PaymentMethodRepository paymentMethodRepository,
            CustomerRepository customerRepository, JdbcTemplate jdbcTemplate, Clock clock) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.customerRepository = customerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public void write(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            RiskContext riskContext) {
        PaymentAttempt attempt = paymentAttemptRepository.findByIdAndMerchantId(
                paymentAttemptId, merchantId).orElseThrow(() -> new IllegalStateException(
                        "Payment attempt disappeared while relation anchors were written"));
        if (!paymentIntentId.equals(attempt.getPaymentIntentId())) {
            throw new IllegalStateException("Payment attempt relation lineage is inconsistent");
        }
        if (existingCount(merchantId, paymentAttemptId) == 0) {
            PaymentMethod method = paymentMethodRepository.findByIdAndMerchantId(
                    attempt.getPaymentMethodId(), merchantId).orElseThrow(() ->
                            new IllegalStateException("Payment method relation lineage is missing"));
            Customer customer = customerRepository.findByIdAndMerchantId(
                    method.getCustomerId(), merchantId).orElseThrow(() ->
                            new IllegalStateException("Customer relation lineage is missing"));
            add(merchantId, paymentIntentId, paymentAttemptId, "SAME_CARD_FINGERPRINT",
                    method.getCardFingerprint(), "correlation-v1", attempt.getCreatedAt(),
                    attempt.getTrafficOrigin().name(), "PAYMENT_METHOD", method.getId());
            add(merchantId, paymentIntentId, paymentAttemptId, "SAME_EMAIL",
                    customer.getEmailHash(), "correlation-v1", attempt.getCreatedAt(),
                    attempt.getTrafficOrigin().name(), "CUSTOMER", customer.getId());
            add(merchantId, paymentIntentId, paymentAttemptId, "SAME_PHONE",
                    customer.getPhoneHash(), "correlation-v1", attempt.getCreatedAt(),
                    attempt.getTrafficOrigin().name(), "CUSTOMER", customer.getId());
        }
        if (riskContext != null && existingRiskContextCount(merchantId, riskContext.getId()) == 0) {
            addVersioned(merchantId, paymentIntentId, paymentAttemptId, "SAME_IP",
                    riskContext.getIpIdentityHmac(), riskContext.getIpIdentityHmacAliases(),
                    riskContext.getIdentityKeyVersion(), riskContext.getObservedAt(),
                    attempt.getTrafficOrigin().name(), riskContext.getId());
            addVersioned(merchantId, paymentIntentId, paymentAttemptId, "SAME_DEVICE",
                    riskContext.getDeviceIdentityHmac(), riskContext.getDeviceIdentityHmacAliases(),
                    riskContext.getIdentityKeyVersion(), riskContext.getObservedAt(),
                    attempt.getTrafficOrigin().name(), riskContext.getId());
            addVersioned(merchantId, paymentIntentId, paymentAttemptId, "SAME_SESSION",
                    riskContext.getSessionIdentityHmac(), riskContext.getSessionIdentityHmacAliases(),
                    riskContext.getIdentityKeyVersion(), riskContext.getObservedAt(),
                    attempt.getTrafficOrigin().name(), riskContext.getId());
        }
    }

    private long existingCount(UUID merchantId, UUID paymentAttemptId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND payment_attempt_id = ?
                  AND source_type IN ('CUSTOMER', 'PAYMENT_METHOD')
                """, Long.class, merchantId, paymentAttemptId);
        return count == null ? 0 : count;
    }

    private long existingRiskContextCount(UUID merchantId, UUID riskContextId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND source_type = 'RISK_CONTEXT' AND source_id = ?
                """, Long.class, merchantId, riskContextId);
        return count == null ? 0 : count;
    }

    private void addVersioned(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            String relationType, String currentDigest, String aliases, String currentKeyVersion,
            Instant observedAt, String trafficOrigin, UUID riskContextId) {
        Map<String, String> values = new LinkedHashMap<>();
        if (currentDigest != null && currentKeyVersion != null) {
            values.put(currentKeyVersion, currentDigest);
        }
        if (aliases != null) {
            for (String alias : aliases.split(",")) {
                int separator = alias.indexOf('=');
                if (separator > 0 && separator < alias.length() - 1) {
                    values.put(alias.substring(0, separator), alias.substring(separator + 1));
                }
            }
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            add(merchantId, paymentIntentId, paymentAttemptId, relationType,
                    value.getValue(), value.getKey(), observedAt, trafficOrigin,
                    "RISK_CONTEXT", riskContextId);
        }
    }

    private void add(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            String relationType, String digest, String keyVersion, Instant observedAt,
            String trafficOrigin, String sourceType, UUID sourceId) {
        if (digest == null || digest.isBlank()) {
            return;
        }
        jdbcTemplate.update(INSERT, UUID.randomUUID(), merchantId, paymentIntentId,
                paymentAttemptId, relationType, digest, keyVersion,
                Timestamp.from(observedAt), trafficOrigin, sourceType, sourceId,
                Timestamp.from(clock.instant()));
    }
}

