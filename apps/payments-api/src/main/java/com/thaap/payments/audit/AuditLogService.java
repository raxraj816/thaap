package com.thaap.payments.audit;

import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.platform.PlatformOperatorContext;
import com.thaap.payments.risk.RiskOperatorContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final Counter auditLogsCreatedCounter;
    private final Counter auditLogsFailedCounter;

    public AuditLogService(AuditLogRepository auditLogRepository, MeterRegistry meterRegistry) {
        this.auditLogRepository = auditLogRepository;
        this.auditLogsCreatedCounter = meterRegistry.counter("thaap.audit.logs.created");
        this.auditLogsFailedCounter = meterRegistry.counter("thaap.audit.logs.failed");
    }

    public void recordMerchantApiAction(MerchantContext merchantContext, String requestId, String action,
            String aggregateType, UUID aggregateId, String reason, Map<String, Object> before,
            Map<String, Object> after) {
        try {
            auditLogRepository.save(new AuditLog(merchantContext.merchantId(), AuditActorType.MERCHANT_API_KEY,
                    actorId(merchantContext), action, aggregateType, aggregateId, requestId, null, reason,
                    AuditSanitizer.sanitize(before), AuditSanitizer.sanitize(after)));
            registerCompletionCounters();
        } catch (RuntimeException exception) {
            auditLogsFailedCounter.increment();
            throw exception;
        }
    }

    public void recordRiskOperatorAction(RiskOperatorContext operatorContext, String requestId, String action,
            String aggregateType, UUID aggregateId, String reason, Map<String, Object> before,
            Map<String, Object> after) {
        try {
            auditLogRepository.save(new AuditLog(operatorContext.merchantId(), AuditActorType.RISK_OPERATOR,
                    operatorContext.operatorCode(), action, aggregateType, aggregateId, requestId, null, reason,
                    AuditSanitizer.sanitize(before), AuditSanitizer.sanitize(after)));
            registerCompletionCounters();
        } catch (RuntimeException exception) {
            auditLogsFailedCounter.increment();
            throw exception;
        }
    }

    public void recordPlatformOperatorAction(PlatformOperatorContext operatorContext,
            String requestId, String action, String aggregateType, UUID aggregateId,
            String reason, Map<String, Object> before, Map<String, Object> after) {
        recordPlatformAction(AuditActorType.PLATFORM_OPERATOR,
                operatorContext.operatorCode(), requestId, action, aggregateType,
                aggregateId, reason, before, after);
    }

    public void recordPlatformBootstrapAction(String requestId, String action,
            String aggregateType, UUID aggregateId, String reason,
            Map<String, Object> before, Map<String, Object> after) {
        recordPlatformAction(AuditActorType.PLATFORM_BOOTSTRAP,
                "platform-bootstrap", requestId, action, aggregateType,
                aggregateId, reason, before, after);
    }

    private void recordPlatformAction(AuditActorType actorType, String actorId,
            String requestId, String action, String aggregateType, UUID aggregateId,
            String reason, Map<String, Object> before, Map<String, Object> after) {
        try {
            auditLogRepository.save(new AuditLog(null, actorType, actorId, action,
                    aggregateType, aggregateId, requestId, null, reason,
                    AuditSanitizer.sanitize(before), AuditSanitizer.sanitize(after)));
            registerCompletionCounters();
        } catch (RuntimeException exception) {
            auditLogsFailedCounter.increment();
            throw exception;
        }
    }

    private String actorId(MerchantContext merchantContext) {
        if (merchantContext.apiKeyPrefix() == null || merchantContext.apiKeyPrefix().isBlank()) {
            return merchantContext.merchantId().toString();
        }
        return merchantContext.apiKeyPrefix();
    }

    private void registerCompletionCounters() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            auditLogsCreatedCounter.increment();
            return;
        }
        // Inside confirm/idempotency transactions, count audit rows only after the surrounding
        // transaction decides whether the row actually committed or rolled back.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                auditLogsCreatedCounter.increment();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    auditLogsFailedCounter.increment();
                }
            }
        });
    }
}
