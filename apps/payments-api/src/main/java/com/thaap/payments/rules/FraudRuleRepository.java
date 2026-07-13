package com.thaap.payments.rules;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface FraudRuleRepository extends JpaRepository<FraudRule, UUID> {

    List<FraudRule> findByRulesetVersionAndStatusOrderByPriorityAscRuleCodeAsc(String rulesetVersion, String status);
}
