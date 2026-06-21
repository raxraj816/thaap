package com.thaap.payments.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class AuditLogServiceTests {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
    }

    @Test
    void recordMerchantApiActionStoresApiKeyPrefixAndSanitizedPayloads() {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant("merchant-a", "thaap_test_secret_key"));
        MerchantContext merchantContext = new MerchantContext(merchant.getId(), merchant.getName(),
                merchant.getApiKeyPrefix());
        UUID aggregateId = UUID.randomUUID();
        double createdBefore = meterRegistry.counter("thaap.audit.logs.created").count();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "REQUIRES_CONFIRMATION");
        before.put("pan", "4242424242424242");
        before.put("cvv", "123");
        before.put("idempotencyKey", "confirm-attempt-a");
        before.put("nullableField", null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "REQUIRES_CAPTURE");
        after.put("paymentIntentId", aggregateId.toString());
        after.put("note", "operator pasted 4999990000000001 by mistake");
        after.put("api_key", "thaap_test_secret_key");
        after.put("contact", Map.of("email", "buyer@example.com", "phone", "+15555550123"));

        auditLogService.recordMerchantApiAction(merchantContext, "req-a", "payment_intent.confirmed",
                "payment_intent", aggregateId, "Confirm completed", before, after);

        AuditLog auditLog = auditLogRepository.findAll().getFirst();
        assertThat(auditLog.getActorId()).isEqualTo(merchant.getApiKeyPrefix());
        assertThat(auditLog.getBefore())
                .containsEntry("status", "REQUIRES_CONFIRMATION")
                .containsEntry("pan", AuditSanitizer.REDACTED)
                .containsEntry("cvv", AuditSanitizer.REDACTED)
                .containsEntry("idempotencyKey", AuditSanitizer.REDACTED)
                .containsEntry("nullableField", null);
        assertThat(auditLog.getAfter())
                .containsEntry("status", "REQUIRES_CAPTURE")
                .containsEntry("paymentIntentId", aggregateId.toString())
                .containsEntry("note", "operator pasted [REDACTED] by mistake")
                .containsEntry("api_key", AuditSanitizer.REDACTED);
        assertThat(auditLog.getAfter().toString())
                .doesNotContain("4999990000000001")
                .doesNotContain("thaap_test_secret_key")
                .doesNotContain("buyer@example.com")
                .doesNotContain("+15555550123");
        assertThat(meterRegistry.counter("thaap.audit.logs.created").count()).isEqualTo(createdBefore + 1.0d);
    }

    @Test
    void failedCounterIncrementsWhenAuditRowCannotCommit() {
        UUID missingMerchantId = UUID.randomUUID();
        MerchantContext merchantContext = new MerchantContext(missingMerchantId, "missing-merchant", "thaap_test");
        UUID aggregateId = UUID.randomUUID();
        double createdBefore = meterRegistry.counter("thaap.audit.logs.created").count();
        double failedBefore = meterRegistry.counter("thaap.audit.logs.failed").count();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                auditLogService.recordMerchantApiAction(merchantContext, "req-missing-merchant",
                        "payment_intent.confirmed", "payment_intent", aggregateId, "Confirm completed", null,
                        Map.of("status", "REQUIRES_CAPTURE"))))
                .isInstanceOf(RuntimeException.class);

        assertThat(auditLogRepository.count()).isZero();
        assertThat(meterRegistry.counter("thaap.audit.logs.created").count()).isEqualTo(createdBefore);
        assertThat(meterRegistry.counter("thaap.audit.logs.failed").count()).isEqualTo(failedBefore + 1.0d);
    }
}
