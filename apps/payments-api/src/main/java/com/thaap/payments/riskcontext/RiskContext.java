package com.thaap.payments.riskcontext;

import com.thaap.payments.tenant.TrafficOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "risk_contexts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_risk_contexts_merchant_id_id", columnNames = {"merchant_id", "id"}),
        @UniqueConstraint(name = "uk_risk_contexts_attempt", columnNames = {"merchant_id", "payment_attempt_id"})
})
public class RiskContext {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "payment_attempt_id", nullable = false) private UUID paymentAttemptId;
    @Column(name = "fraud_decision_id") private UUID fraudDecisionId;
    @Column(name = "ip_identity_hmac", length = 64) private String ipIdentityHmac;
    @Column(name = "user_agent_identity_hmac", length = 64) private String userAgentIdentityHmac;
    @Column(name = "device_identity_hmac", length = 64) private String deviceIdentityHmac;
    @Column(name = "session_identity_hmac", length = 64) private String sessionIdentityHmac;
    @Column(name = "ip_identity_hmac_aliases", columnDefinition = "text") private String ipIdentityHmacAliases;
    @Column(name = "device_identity_hmac_aliases", columnDefinition = "text") private String deviceIdentityHmacAliases;
    @Column(name = "session_identity_hmac_aliases", columnDefinition = "text") private String sessionIdentityHmacAliases;
    @Column(name = "identity_key_version", length = 32) private String identityKeyVersion;
    @Column(name = "ip_country", length = 2) private String ipCountry;
    @Column(name = "shipping_country", length = 2) private String shippingCountry;
    @Enumerated(EnumType.STRING)
    @Column(name = "checkout_channel", length = 16) private CheckoutChannel checkoutChannel;
    @Column(name = "customer_account_age_days") private Integer customerAccountAgeDays;
    @Column(name = "login_age_minutes") private Integer loginAgeMinutes;
    @Column(name = "bot_score_bps") private Integer botScoreBps;
    @Enumerated(EnumType.STRING)
    @Column(name = "captcha_outcome", length = 16) private CaptchaOutcome captchaOutcome;
    @Column(name = "observed_at", nullable = false) private Instant observedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "traffic_origin", nullable = false, length = 32) private TrafficOrigin trafficOrigin;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected RiskContext() {
    }

    RiskContext(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            String ipIdentityHmac, String userAgentIdentityHmac, String deviceIdentityHmac,
            String sessionIdentityHmac, String ipIdentityHmacAliases,
            String deviceIdentityHmacAliases, String sessionIdentityHmacAliases,
            String identityKeyVersion, RiskContextRequest request,
            TrafficOrigin trafficOrigin, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.paymentIntentId = Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        this.paymentAttemptId = Objects.requireNonNull(paymentAttemptId, "paymentAttemptId");
        this.ipIdentityHmac = ipIdentityHmac;
        this.userAgentIdentityHmac = userAgentIdentityHmac;
        this.deviceIdentityHmac = deviceIdentityHmac;
        this.sessionIdentityHmac = sessionIdentityHmac;
        this.ipIdentityHmacAliases = ipIdentityHmacAliases;
        this.deviceIdentityHmacAliases = deviceIdentityHmacAliases;
        this.sessionIdentityHmacAliases = sessionIdentityHmacAliases;
        this.identityKeyVersion = identityKeyVersion;
        this.ipCountry = request.ipCountry();
        this.shippingCountry = request.shippingCountry();
        this.checkoutChannel = request.checkoutChannel();
        this.customerAccountAgeDays = request.customerAccountAgeDays();
        this.loginAgeMinutes = request.loginAgeMinutes();
        this.botScoreBps = request.botScoreBps();
        this.captchaOutcome = request.captchaOutcome();
        this.observedAt = request.observedAt() == null ? now : request.observedAt();
        this.trafficOrigin = Objects.requireNonNull(trafficOrigin, "trafficOrigin");
        this.createdAt = now;
    }

    public void attachFraudDecision(UUID fraudDecisionId) {
        if (this.fraudDecisionId != null && !this.fraudDecisionId.equals(fraudDecisionId)) {
            throw new IllegalStateException("RiskContext decision lineage is immutable");
        }
        this.fraudDecisionId = Objects.requireNonNull(fraudDecisionId, "fraudDecisionId");
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public UUID getFraudDecisionId() { return fraudDecisionId; }
    public String getIpCountry() { return ipCountry; }
    public String getShippingCountry() { return shippingCountry; }
    public CheckoutChannel getCheckoutChannel() { return checkoutChannel; }
    public Integer getCustomerAccountAgeDays() { return customerAccountAgeDays; }
    public Integer getLoginAgeMinutes() { return loginAgeMinutes; }
    public Integer getBotScoreBps() { return botScoreBps; }
    public CaptchaOutcome getCaptchaOutcome() { return captchaOutcome; }
    public Instant getObservedAt() { return observedAt; }
    public String getIdentityKeyVersion() { return identityKeyVersion; }
    public String getIpIdentityHmac() { return ipIdentityHmac; }
    public String getDeviceIdentityHmac() { return deviceIdentityHmac; }
    public String getSessionIdentityHmac() { return sessionIdentityHmac; }

    public String getIpIdentityHmac(String keyVersion) {
        return identityForVersion(ipIdentityHmac, ipIdentityHmacAliases, keyVersion);
    }

    public String getDeviceIdentityHmac(String keyVersion) {
        return identityForVersion(deviceIdentityHmac, deviceIdentityHmacAliases, keyVersion);
    }

    public String getSessionIdentityHmac(String keyVersion) {
        return identityForVersion(sessionIdentityHmac, sessionIdentityHmacAliases, keyVersion);
    }

    private String identityForVersion(String currentIdentity, String aliases, String keyVersion) {
        if (keyVersion == null) {
            return null;
        }
        if (keyVersion.equals(identityKeyVersion)) {
            return currentIdentity;
        }
        if (aliases == null || aliases.isBlank()) {
            return null;
        }
        for (String alias : aliases.split(",")) {
            int separator = alias.indexOf('=');
            if (separator > 0 && keyVersion.equals(alias.substring(0, separator))) {
                return alias.substring(separator + 1);
            }
        }
        return null;
    }
}
