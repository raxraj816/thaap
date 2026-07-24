package com.thaap.payments.incident;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentEvidenceLinkRepository extends JpaRepository<IncidentEvidenceLink, UUID> {
    boolean existsByMerchantIdAndIncidentIdAndPaymentAttemptId(
            UUID merchantId, UUID incidentId, UUID paymentAttemptId);
    boolean existsByMerchantIdAndPaymentAttemptId(UUID merchantId, UUID paymentAttemptId);
}
