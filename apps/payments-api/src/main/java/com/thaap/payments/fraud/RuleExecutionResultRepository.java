package com.thaap.payments.fraud;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleExecutionResultRepository extends JpaRepository<RuleExecutionResult, UUID> {
    List<RuleExecutionResult> findByFraudDecisionIdAndMerchantIdOrderByPriorityAscRuleCodeAsc(
            UUID fraudDecisionId, UUID merchantId);
}
