package com.thaap.payments.fraud;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class FeatureCatalogue {

    public static final String VERSION = "v1";
    private static final Pattern FEATURE_CODE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final Map<String, FeatureValueType> REQUIRED_TYPES;

    static {
        Map<String, FeatureValueType> types = new LinkedHashMap<>();
        types.put("card.country", FeatureValueType.STRING);
        types.put("card.funding", FeatureValueType.STRING);
        types.put("payment.amount_minor", FeatureValueType.LONG);
        types.put("payment.attempt_number", FeatureValueType.LONG);
        types.put("payment.capture_method", FeatureValueType.STRING);
        types.put("payment.currency", FeatureValueType.STRING);
        types.put("traffic.origin", FeatureValueType.STRING);
        types.put("merchant.risk_tier", FeatureValueType.STRING);
        types.put("risk.context_present", FeatureValueType.BOOLEAN);
        types.put("risk.ip_country", FeatureValueType.STRING);
        types.put("risk.shipping_country", FeatureValueType.STRING);
        types.put("risk.checkout_channel", FeatureValueType.STRING);
        types.put("risk.customer_account_age_days", FeatureValueType.LONG);
        types.put("risk.login_age_minutes", FeatureValueType.LONG);
        types.put("risk.bot_score_bps", FeatureValueType.LONG);
        types.put("risk.captcha_outcome", FeatureValueType.STRING);
        types.put("risk.card_ip_country_match", FeatureValueType.BOOLEAN);
        types.put("risk.card_shipping_country_match", FeatureValueType.BOOLEAN);
        types.put("velocity.card_attempts_10m", FeatureValueType.LONG);
        types.put("velocity.ip_attempts_10m", FeatureValueType.LONG);
        types.put("velocity.customer_attempts_1h", FeatureValueType.LONG);
        types.put("velocity.merchant_attempts_1m", FeatureValueType.LONG);
        REQUIRED_TYPES = Collections.unmodifiableMap(types);
    }

    private FeatureCatalogue() {
    }

    public static Set<String> requiredCodes() {
        return REQUIRED_TYPES.keySet();
    }

    public static FeatureValueType requiredType(String code) {
        return REQUIRED_TYPES.get(code);
    }

    public static boolean isAlwaysPresent(String code) {
        return (!code.startsWith("risk.") && !code.startsWith("velocity."))
                || "risk.context_present".equals(code);
    }

    public static boolean isLexicallyValid(String code) {
        return code != null && FEATURE_CODE.matcher(code).matches();
    }
}
