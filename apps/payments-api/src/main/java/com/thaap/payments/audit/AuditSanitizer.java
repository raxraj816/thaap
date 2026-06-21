package com.thaap.payments.audit;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class AuditSanitizer {

    static final String REDACTED = "[REDACTED]";

    private static final Pattern LONG_DIGIT_SEQUENCE = Pattern.compile("\\d{13,19}");
    private static final List<String> SHORT_SENSITIVE_KEYS = List.of("dob", "ssn");
    private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
            "cardnumber",
            "card_number",
            "card_fingerprint",
            "cvv",
            "cvc",
            "api_key",
            "apikey",
            "bank_account",
            "bank_account_number",
            "date_of_birth",
            "fingerprint",
            "secret",
            "idempotency",
            "routing_number",
            "stripe_payment_method",
            "tax_id",
            "token",
            "password",
            "email",
            "phone");

    private AuditSanitizer() {
    }

    static Map<String, Object> sanitize(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            sanitized.put(key, sanitizeValue(key, entry.getValue()));
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        if (value instanceof String stringValue) {
            return LONG_DIGIT_SEQUENCE.matcher(stringValue).replaceAll(REDACTED);
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                String nestedKey = String.valueOf(entry.getKey());
                sanitized.put(nestedKey, sanitizeValue(nestedKey, entry.getValue()));
            }
            return Collections.unmodifiableMap(sanitized);
        }
        if (value instanceof Collection<?> collectionValue) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : collectionValue) {
                sanitized.add(sanitizeValue("", item));
            }
            return Collections.unmodifiableList(sanitized);
        }
        if (value.getClass().isArray()) {
            List<Object> sanitized = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                sanitized.add(sanitizeValue("", Array.get(value, index)));
            }
            return Collections.unmodifiableList(sanitized);
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String compactKey = normalizedKey.replace("-", "").replace("_", "");
        if (isPanKey(normalizedKey, compactKey)) {
            return true;
        }
        for (String shortKey : SHORT_SENSITIVE_KEYS) {
            if (isShortSensitiveKey(normalizedKey, compactKey, shortKey)) {
                return true;
            }
        }
        for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
            String compactFragment = fragment.replace("_", "");
            if (normalizedKey.contains(fragment) || compactKey.contains(compactFragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPanKey(String normalizedKey, String compactKey) {
        return normalizedKey.equals("pan")
                || normalizedKey.endsWith("_pan")
                || normalizedKey.endsWith("-pan")
                || compactKey.equals("cardpan");
    }

    private static boolean isShortSensitiveKey(String normalizedKey, String compactKey, String shortKey) {
        return normalizedKey.equals(shortKey)
                || normalizedKey.startsWith(shortKey + "_")
                || normalizedKey.startsWith(shortKey + "-")
                || normalizedKey.endsWith("_" + shortKey)
                || normalizedKey.endsWith("-" + shortKey)
                || compactKey.startsWith(shortKey)
                || compactKey.endsWith(shortKey);
    }
}
