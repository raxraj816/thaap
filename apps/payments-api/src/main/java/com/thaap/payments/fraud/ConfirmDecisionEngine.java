package com.thaap.payments.fraud;

public interface ConfirmDecisionEngine {

    ConfirmDecision decide(DecisionFeatureContext featureContext);
}
