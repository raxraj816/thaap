package com.thaap.payments.fraud;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StubConfirmDecisionEngine implements ConfirmDecisionEngine {

    @Override
    public ConfirmDecision decide(ConfirmDecisionRequest request) {
        return new ConfirmDecision(FraudDecisionAction.ALLOW, List.of(FraudDecisionReasonCode.RULES_NOT_ENABLED));
    }
}
