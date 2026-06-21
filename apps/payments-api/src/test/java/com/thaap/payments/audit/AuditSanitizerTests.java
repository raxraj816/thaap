package com.thaap.payments.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditSanitizerTests {

    @Test
    void redactsSensitiveKeysAndLongDigitSequencesRecursively() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", "pi_123");
        payload.put("status", "AUTHORIZED");
        payload.put("pan", "4242424242424242");
        payload.put("apiKey", "thaap_test_secret");
        payload.put("idempotencyKey", "confirm-attempt-a");
        payload.put("idempotency_key", "confirm-attempt-b");
        payload.put("idempotency-key", "confirm-attempt-c");
        payload.put("fingerprint", "fp_plain");
        payload.put("card_fingerprint", "fp_card");
        payload.put("stripe_payment_method", "pm_stripe_secret");
        payload.put("bank_account_number", "000123456789");
        payload.put("routing_number", "021000021");
        payload.put("ssn", "123-45-6789");
        payload.put("tax_id", "12-3456789");
        payload.put("date_of_birth", "1970-01-01");
        payload.put("dob", "1970-01-02");
        payload.put("businessName", "A Better Store");
        payload.put("note", "accidental card 4999990000000001 in text");
        payload.put("nested", Map.of(
                "cvv", "123",
                "email", "buyer@example.com",
                "reasonCodes", List.of("RULES_NOT_ENABLED"),
                "bank-account", "ba_secret",
                "routing-number", "routing_secret"));
        payload.put("events", List.of(Map.of("phone", "+15555550123"), "processor ref 1234567890123456789"));

        Map<String, Object> sanitized = AuditSanitizer.sanitize(payload);

        assertThat(sanitized)
                .containsEntry("paymentIntentId", "pi_123")
                .containsEntry("status", "AUTHORIZED")
                .containsEntry("pan", AuditSanitizer.REDACTED)
                .containsEntry("apiKey", AuditSanitizer.REDACTED)
                .containsEntry("idempotencyKey", AuditSanitizer.REDACTED)
                .containsEntry("idempotency_key", AuditSanitizer.REDACTED)
                .containsEntry("idempotency-key", AuditSanitizer.REDACTED)
                .containsEntry("fingerprint", AuditSanitizer.REDACTED)
                .containsEntry("card_fingerprint", AuditSanitizer.REDACTED)
                .containsEntry("stripe_payment_method", AuditSanitizer.REDACTED)
                .containsEntry("bank_account_number", AuditSanitizer.REDACTED)
                .containsEntry("routing_number", AuditSanitizer.REDACTED)
                .containsEntry("ssn", AuditSanitizer.REDACTED)
                .containsEntry("tax_id", AuditSanitizer.REDACTED)
                .containsEntry("date_of_birth", AuditSanitizer.REDACTED)
                .containsEntry("dob", AuditSanitizer.REDACTED)
                .containsEntry("businessName", "A Better Store")
                .containsEntry("note", "accidental card [REDACTED] in text");
        assertThat(sanitized.toString())
                .doesNotContain("4242424242424242")
                .doesNotContain("4999990000000001")
                .doesNotContain("thaap_test_secret")
                .doesNotContain("confirm-attempt-a")
                .doesNotContain("confirm-attempt-b")
                .doesNotContain("confirm-attempt-c")
                .doesNotContain("fp_plain")
                .doesNotContain("fp_card")
                .doesNotContain("pm_stripe_secret")
                .doesNotContain("000123456789")
                .doesNotContain("021000021")
                .doesNotContain("123-45-6789")
                .doesNotContain("12-3456789")
                .doesNotContain("1970-01-01")
                .doesNotContain("1970-01-02")
                .doesNotContain("ba_secret")
                .doesNotContain("routing_secret")
                .doesNotContain("buyer@example.com")
                .doesNotContain("+15555550123")
                .doesNotContain("1234567890123456789");
    }
}
