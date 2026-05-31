package com.thaap.payments.fraud;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudDecisionRepository extends JpaRepository<FraudDecision, UUID> {

    List<FraudDecision> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);
}
