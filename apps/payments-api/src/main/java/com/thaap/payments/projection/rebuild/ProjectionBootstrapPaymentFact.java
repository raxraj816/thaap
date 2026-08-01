package com.thaap.payments.projection.rebuild;

import java.time.Instant;
import java.util.UUID;

/** Immutable, privacy-safe source snapshot for one payment that predates event production. */
public record ProjectionBootstrapPaymentFact(
        int schemaVersion,
        UUID merchantId,
        UUID paymentIntentId,
        long intentAmountMinor,
        String currency,
        String captureMethod,
        String trafficOrigin,
        Instant intentOccurredAt,
        String lifecycleStatus,
        Integer attemptNumber,
        String attemptOutcome,
        String processorCode,
        long attemptCount,
        long declineCount,
        long unknownOutcomeCount,
        String fraudAction,
        String riskLevel,
        Integer riskScore,
        String reasonCodesJson,
        String rulesetVersion,
        String featureCatalogueVersion,
        UUID fraudDecisionId,
        UUID featureSnapshotId,
        long authorizationCount,
        long authorizedAmountMinor,
        String reviewStatus,
        String reviewOutcome,
        long reviewCount,
        long capturedAmountMinor,
        long refundedAmountMinor,
        long disputedAmountMinor,
        long chargebackCount,
        String labelValue,
        String fraudType,
        String labelSource,
        Instant labelAvailableAt,
        long baseStreamVersion,
        UUID baseTerminalDomainEventId,
        String baseTerminalEventRecordSha256,
        Instant sourceCutoffAt) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ProjectionBootstrapPaymentFact {
        if ((schemaVersion != 1 && schemaVersion != CURRENT_SCHEMA_VERSION) || merchantId == null
                || paymentIntentId == null || intentAmountMinor < 0
                || currency == null || currency.length() != 3
                || captureMethod == null || trafficOrigin == null
                || intentOccurredAt == null || lifecycleStatus == null
                || attemptCount < 0 || declineCount < 0 || unknownOutcomeCount < 0
                || authorizationCount < 0 || authorizedAmountMinor < 0
                || reviewCount < 0 || capturedAmountMinor < 0
                || refundedAmountMinor < 0 || disputedAmountMinor < 0
                || chargebackCount < 0 || baseStreamVersion < 0
                || sourceCutoffAt == null || sourceCutoffAt.isBefore(intentOccurredAt)) {
            throw new IllegalArgumentException("Projection bootstrap payment fact is invalid");
        }
        boolean emptyBase = baseStreamVersion == 0
                && baseTerminalDomainEventId == null
                && baseTerminalEventRecordSha256 == null;
        boolean eventBase = baseStreamVersion > 0
                && baseTerminalDomainEventId != null
                && baseTerminalEventRecordSha256 != null
                && baseTerminalEventRecordSha256.length() == 64;
        if (!emptyBase && !eventBase) {
            throw new IllegalArgumentException("Projection bootstrap event base is invalid");
        }
        boolean noDecisionLineage = fraudDecisionId == null && featureSnapshotId == null;
        boolean exactDecisionLineage = fraudDecisionId != null && featureSnapshotId != null;
        if (!noDecisionLineage && !exactDecisionLineage) {
            throw new IllegalArgumentException("Projection bootstrap decision lineage is incomplete");
        }
        if (schemaVersion == CURRENT_SCHEMA_VERSION
                && ((fraudAction == null) != noDecisionLineage)) {
            throw new IllegalArgumentException("Projection bootstrap decision lineage is inconsistent");
        }
    }
}
