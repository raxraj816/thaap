package com.thaap.payments.fraudlist;

import com.thaap.payments.fraud.FraudDecisionAction;
import java.time.Instant;
import java.util.UUID;

public record FraudListEvaluation(UUID listId, UUID versionId, int versionNumber, String listCode,
        FraudListIdentityType identityType, UUID matchedEntryId, FraudDecisionAction candidateAction,
        FraudListReasonCode reasonCode, Instant evaluatedAt) {

    public boolean matched() {
        return matchedEntryId != null;
    }
}
