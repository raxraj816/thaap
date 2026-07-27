package com.thaap.payments.dispute;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DisputeExpiryScheduler {

    private final DisputeOperationsService service;
    private final boolean enabled;
    private final int batchSize;

    public DisputeExpiryScheduler(DisputeOperationsService service,
            @Value("${thaap.disputes.expiry-enabled:true}") boolean enabled,
            @Value("${thaap.disputes.expiry-batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Dispute expiry batch size must be between 1 and 100");
        }
        this.service = service;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${thaap.disputes.expiry-poll-delay:1m}")
    public void expireDue() {
        if (enabled) {
            service.expireDue(batchSize);
        }
    }
}
