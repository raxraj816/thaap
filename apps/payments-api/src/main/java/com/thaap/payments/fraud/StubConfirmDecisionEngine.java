package com.thaap.payments.fraud;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StubConfirmDecisionEngine implements ConfirmDecisionEngine {

    @Override
    public ConfirmDecision decide(ConfirmDecisionRequest request) {
        return new ConfirmDecision(FraudDecisionAction.ALLOW, BigDecimal.ZERO, FraudRiskLevel.LOW, "rules_stub_v1",
                null, List.of(FraudDecisionReasonCode.RULES_NOT_ENABLED));
    }
}
