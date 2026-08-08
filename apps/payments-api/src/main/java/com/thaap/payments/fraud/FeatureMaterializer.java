package com.thaap.payments.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentattempt.PaymentAttempt;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.riskcontext.RiskContext;
import com.thaap.payments.riskpolicy.MerchantRiskTier;
import com.thaap.payments.time.DatabaseInstant;
import com.thaap.payments.velocity.VelocityAssessment;
import com.thaap.payments.velocity.VelocityAvailability;
import com.thaap.payments.velocity.VelocitySignal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FeatureMaterializer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private final FeatureDefinitionRepository featureDefinitionRepository;
    private final FeatureCatalogueValidator catalogueValidator;
    private final FeatureMaterializationMetrics metrics;
    private final int maxSnapshotJsonBytes;

    public FeatureMaterializer(FeatureDefinitionRepository featureDefinitionRepository,
            FeatureCatalogueValidator catalogueValidator, FeatureMaterializationMetrics metrics,
            @Value("${thaap.fraud.feature-snapshot.max-json-bytes:16384}") int maxSnapshotJsonBytes) {
        if (maxSnapshotJsonBytes <= 0) {
            throw new IllegalArgumentException("thaap.fraud.feature-snapshot.max-json-bytes must be positive");
        }
        this.featureDefinitionRepository = featureDefinitionRepository;
        this.catalogueValidator = catalogueValidator;
        this.metrics = metrics;
        this.maxSnapshotJsonBytes = maxSnapshotJsonBytes;
    }

    public MaterializedFeatureSet materialize(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            PaymentMethod paymentMethod, Instant decisionStartedAt) {
        return materialize(paymentIntent, paymentAttempt, paymentMethod, null, decisionStartedAt);
    }

    public MaterializedFeatureSet materialize(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            PaymentMethod paymentMethod, RiskContext riskContext, Instant decisionStartedAt) {
        return materialize(paymentIntent, paymentAttempt, paymentMethod, riskContext,
                VelocityAssessment.disabled(), MerchantRiskTier.STANDARD, decisionStartedAt);
    }

    public MaterializedFeatureSet materialize(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            PaymentMethod paymentMethod, RiskContext riskContext, VelocityAssessment velocityAssessment,
            Instant decisionStartedAt) {
        return materialize(paymentIntent, paymentAttempt, paymentMethod, riskContext, velocityAssessment,
                MerchantRiskTier.STANDARD, decisionStartedAt);
    }

    public MaterializedFeatureSet materialize(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            PaymentMethod paymentMethod, RiskContext riskContext, VelocityAssessment velocityAssessment,
            MerchantRiskTier merchantRiskTier, Instant decisionStartedAt) {
        long startedNanos = System.nanoTime();
        try {
            List<FeatureDefinition> definitions =
                    featureDefinitionRepository.findAllByOrderByFeatureCodeAscFeatureVersionAsc();
            catalogueValidator.validate(definitions);
            Map<String, FeatureDefinition> byCode = definitionsByCode(definitions);
            Instant computedAt = DatabaseInstant.now();
            Map<String, SourceValue> sources = sourceValues(paymentIntent, paymentAttempt, paymentMethod, riskContext,
                    velocityAssessment, merchantRiskTier);
            List<FeatureValue> values = new ArrayList<>();
            Map<String, Object> snapshot = new TreeMap<>();
            List<String> staleNames = new ArrayList<>();
            List<String> missingNames = new ArrayList<>();
            Instant oldest = null;
            long maxAgeMs = 0L;

            for (String code : FeatureCatalogue.requiredCodes()) {
                FeatureDefinition definition = requireDefinition(byCode, code);
                SourceValue source = sources.get(code);
                if (source == null || source.value() == null || source.observedAt() == null) {
                    if (!FeatureCatalogue.isAlwaysPresent(code)) {
                        missingNames.add(code);
                        continue;
                    }
                    throw new FeatureMaterializationException("MISSING_FEATURE", code,
                            "Required feature is missing");
                }
                if (source.observedAt().isAfter(decisionStartedAt)) {
                    throw new FeatureMaterializationException("FUTURE_OBSERVATION", code,
                            "Feature observation is after decision start");
                }
                long ageMs = Math.max(0L, Duration.between(source.observedAt(), decisionStartedAt).toMillis());
                maxAgeMs = Math.max(maxAgeMs, ageMs);
                oldest = oldest == null || source.observedAt().isBefore(oldest) ? source.observedAt() : oldest;
                if (definition.getMaxAgeMs() != null && ageMs > definition.getMaxAgeMs()) {
                    staleNames.add(code);
                }
                FeatureValue featureValue = new FeatureValue(paymentIntent.getMerchantId(), paymentAttempt.getId(),
                        definition, source.value(), source.observedAt(), computedAt, decisionStartedAt,
                        paymentAttempt.getTrafficOrigin());
                values.add(featureValue);
                snapshot.put(code, featureValue.typedValue());
            }
            requireSnapshotWithinStorageGuard(snapshot);
            FeatureFreshnessStatus freshness = staleNames.isEmpty()
                    ? FeatureFreshnessStatus.FRESH : FeatureFreshnessStatus.STALE;
            metrics.recordFreshness(freshness, paymentAttempt.getTrafficOrigin());
            metrics.recordMaterializationLatency("SUCCESS", paymentAttempt.getTrafficOrigin(),
                    System.nanoTime() - startedNanos);
            return new MaterializedFeatureSet(new DecisionFeatureContext(snapshot), values, computedAt, oldest, maxAgeMs,
                    freshness, List.copyOf(staleNames), List.copyOf(missingNames));
        } catch (FeatureMaterializationException exception) {
            metrics.recordFailure("MATERIALIZATION", exception.causeCode(), exception.featureCode());
            metrics.recordMaterializationLatency("FAILURE", paymentAttempt.getTrafficOrigin(),
                    System.nanoTime() - startedNanos);
            throw exception;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            metrics.recordFailure("VALIDATION", "INVALID_CATALOGUE_OR_VALUE");
            metrics.recordMaterializationLatency("FAILURE", paymentAttempt.getTrafficOrigin(),
                    System.nanoTime() - startedNanos);
            throw new FeatureMaterializationException("INVALID_CATALOGUE_OR_VALUE",
                    "Feature materialization validation failed", exception);
        }
    }

    private Map<String, FeatureDefinition> definitionsByCode(List<FeatureDefinition> definitions) {
        Map<String, FeatureDefinition> byCode = new HashMap<>();
        for (FeatureDefinition definition : definitions) {
            if (FeatureCatalogue.VERSION.equals(definition.getFeatureVersion())) {
                byCode.put(definition.getFeatureCode(), definition);
            }
        }
        return byCode;
    }

    private FeatureDefinition requireDefinition(Map<String, FeatureDefinition> definitions, String code) {
        FeatureDefinition definition = definitions.get(code);
        if (definition == null) {
            throw new FeatureMaterializationException("MISSING_DEFINITION", "Required feature definition is missing");
        }
        return definition;
    }

    private Map<String, SourceValue> sourceValues(PaymentIntent paymentIntent, PaymentAttempt paymentAttempt,
            PaymentMethod paymentMethod, RiskContext riskContext, VelocityAssessment velocityAssessment,
            MerchantRiskTier merchantRiskTier) {
        Map<String, SourceValue> values = new HashMap<>();
        values.put("payment.amount_minor", new SourceValue(paymentIntent.getAmount(), paymentIntent.getCreatedAt()));
        values.put("payment.currency", new SourceValue(paymentIntent.getCurrency(), paymentIntent.getCreatedAt()));
        values.put("payment.capture_method",
                new SourceValue(paymentIntent.getCaptureMethod().name(), paymentIntent.getCreatedAt()));
        values.put("payment.attempt_number",
                new SourceValue((long) paymentAttempt.getAttemptNumber(), paymentAttempt.getCreatedAt()));
        values.put("card.country", new SourceValue(paymentMethod.getCardCountry(), paymentMethod.getCreatedAt()));
        values.put("card.funding", new SourceValue(paymentMethod.getCardFunding().name(), paymentMethod.getCreatedAt()));
        values.put("traffic.origin",
                new SourceValue(paymentAttempt.getTrafficOrigin().name(), paymentAttempt.getCreatedAt()));
        values.put("merchant.risk_tier",
                new SourceValue(merchantRiskTier.name(), paymentAttempt.getCreatedAt()));
        values.put("risk.context_present", new SourceValue(riskContext != null, paymentAttempt.getCreatedAt()));
        if (riskContext != null) {
            Instant observedAt = riskContext.getObservedAt();
            values.put("risk.ip_country", new SourceValue(riskContext.getIpCountry(), observedAt));
            values.put("risk.shipping_country", new SourceValue(riskContext.getShippingCountry(), observedAt));
            values.put("risk.checkout_channel", new SourceValue(
                    riskContext.getCheckoutChannel() == null ? null : riskContext.getCheckoutChannel().name(), observedAt));
            values.put("risk.customer_account_age_days", new SourceValue(
                    riskContext.getCustomerAccountAgeDays() == null ? null
                            : riskContext.getCustomerAccountAgeDays().longValue(), observedAt));
            values.put("risk.login_age_minutes", new SourceValue(
                    riskContext.getLoginAgeMinutes() == null ? null : riskContext.getLoginAgeMinutes().longValue(), observedAt));
            values.put("risk.bot_score_bps", new SourceValue(
                    riskContext.getBotScoreBps() == null ? null : riskContext.getBotScoreBps().longValue(), observedAt));
            values.put("risk.captcha_outcome", new SourceValue(
                    riskContext.getCaptchaOutcome() == null ? null : riskContext.getCaptchaOutcome().name(), observedAt));
            values.put("risk.card_ip_country_match", new SourceValue(
                    riskContext.getIpCountry() == null ? null
                            : riskContext.getIpCountry().equals(paymentMethod.getCardCountry()), observedAt));
            values.put("risk.card_shipping_country_match", new SourceValue(
                    riskContext.getShippingCountry() == null ? null
                            : riskContext.getShippingCountry().equals(paymentMethod.getCardCountry()), observedAt));
        }
        for (VelocitySignal signal : velocityAssessment.signals()) {
            if (signal.availability() == VelocityAvailability.AVAILABLE) {
                values.put(signal.dimension().featureCode(),
                        new SourceValue(signal.otherAttemptCount(), signal.observedAt()));
            }
        }
        return values;
    }

    private void requireSnapshotWithinStorageGuard(Map<String, Object> snapshot) {
        try {
            int serializedBytes = OBJECT_MAPPER.writeValueAsString(snapshot).getBytes(StandardCharsets.UTF_8).length;
            if (serializedBytes > maxSnapshotJsonBytes) {
                throw new FeatureMaterializationException("SNAPSHOT_TOO_LARGE",
                        "Feature snapshot exceeded safe storage limit");
            }
        } catch (FeatureMaterializationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FeatureMaterializationException("SERIALIZATION_ERROR",
                    "Feature snapshot could not be serialized", exception);
        }
    }

    private record SourceValue(Object value, Instant observedAt) {
    }
}
