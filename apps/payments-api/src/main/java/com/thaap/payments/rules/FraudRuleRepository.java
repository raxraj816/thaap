package com.thaap.payments.rules;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudRuleRepository extends JpaRepository<FraudRule, UUID> {

    List<FraudRule> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);
}
