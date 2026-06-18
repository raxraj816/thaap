package com.thaap.payments.fraud;

import java.util.List;

public record ConfirmDecision(FraudDecisionAction action, List<FraudDecisionReasonCode> reasonCodes) {
}
