export type QueueRow = {
  id: string;
  paymentIntentId: string;
  decisionAction: string;
  reasonCodes: string[];
  status: string;
  reviewStage: string;
  assignedOperatorId: string | null;
  assignmentVersion: number;
  slaState: "BREACHED" | "WITHIN_SLA";
  createdAt: string;
};

export type QueuePage = {
  asOf: string;
  rows: QueueRow[];
  nextCursor: string | null;
};

export type SavedSearch = {
  id: string;
  name: string;
  visibility: "PRIVATE" | "MERCHANT_SHARED";
  lifecycleVersion: number;
};

export type ReviewNote = {
  id: string;
  lifecycleStatus: "ACTIVE" | "REDACTED" | "EXPIRED";
  supersedesNoteId: string | null;
  createdAt: string;
  content: string | null;
};

export type ApiFailure = Error & { status?: number; requestId?: string };

export type SectionStatus = {
  state: "AVAILABLE" | "EMPTY" | "NOT_APPLICABLE" | "PARTIAL" | "STALE" | "UNAVAILABLE" | "DENIED";
  authority: string;
  reasonCode: string | null;
};

export type InvestigationDetail = {
  identity: { paymentIntentId: string; latestPaymentAttemptId: string | null;
    latestFraudDecisionId: string | null; latestReviewCaseId: string | null; trafficOrigin: string };
  sourceSnapshot: { sourceAsOf: string; maximumPaymentStreamVersion: number; consistency: string; authority: string };
  analyticsContext: { freshness: AnalyticsFreshness } | null;
  paymentLifecycle: { status: string; amountMinor: number; currency: string; captureMethod: string;
    createdAt: string; updatedAt: string; attempts: { id: string; attemptNumber: number;
      status: string; fraudDecisionId: string | null; threeDsStatus: string | null;
      trafficOrigin: string; createdAt: string; updatedAt: string }[] };
  decisionExplanation: { riskScoreBps: number; riskLevel: string; finalAction: string; reasonCodes: string[];
    modelState: string;
    controlEvaluations: { family: string; code: string; version: string | null;
      availability: string; outcome: string; matched: boolean; proposedAction: string | null;
      reason: string; contributedToWinningAction: boolean }[];
    precedence: { winningControl: string; winningAction: string } } | null;
  reviewAndLabels: { reviews: { id: string; status: string; reviewStage: string;
    assignedOperatorId: string | null; assignmentVersion: number }[];
    labels: { id: string; value: string; fraudType: string | null; source: string }[] };
  moneyAndDisputes: { currency: string; capturedAmountMinor: number; refundedAmountMinor: number;
    disputedAmountMinor: number; ledgerDebitMinor: number; ledgerCreditMinor: number;
    ledgerBalanced: boolean; disputes: { id: string; amountMinor: number;
      allocatedAmountMinor: number; currency: string; reasonCode: string; status: string }[] };
  webhookSummary: { deliveryCount: number; statuses: { status: string; deliveryCount: number;
    attemptCount: number; lastAttemptAt: string | null; lastDeliveredAt: string | null }[] };
  availableActions: { action: string; eligible: boolean; reasonCode: string | null }[];
  sectionStatuses: Record<string, SectionStatus>;
};

export type InvestigationSearchPage = {
  data: { paymentIntentId: string; amountMinor: number | null; currency: string | null;
    lifecycleStatus: string | null; fraudAction: string | null; riskLevel: string | null;
    riskScoreBasisPoints: number | null; reviewStatus: string | null; labelValue: string | null;
    trafficOrigin: string | null; coverageStatus: string; lastEventRecordedAt: string }[];
  nextCursor: string | null;
  freshness: AnalyticsFreshness;
};

export type TimelinePage = { maximumStreamVersion: number; data: { eventId: string;
  streamVersion: number; eventType: string; occurredAt: string; recordedAt: string;
  category: string; titleCode: string; safeFacts: Record<string, unknown> }[]; nextCursor: string | null;
  ordering: string; state: SectionStatus["state"] };

export type RelatedPaymentPage = { from: string; to: string; asOf: string;
  data: { paymentIntentId: string; reasons: string[]; matchedReasonCount: number;
    paymentAndDecision: { lifecycleStatus: string | null; fraudAction: string | null;
      riskLevel: string | null; coverageStatus: string } | null;
    sectionState: string }[]; nextCursor: string | null;
  coverage: { state: string; availableReasons: string[]; unavailableReasons: string[];
    reasonCode: string | null } };

/** A replayable, tenant-free filter that reproduces one insight segment. */
export type InsightDrillThrough = { fraudActions: string[]; createdFrom: string;
  createdTo: string; trafficOriginPolicy: string };

export type WorkspaceDecisionInsights = { range: { from: string; to: string };
  trafficOriginPolicy: string; freshness: AnalyticsFreshness;
  decisionDistribution: { code: string; count: number;
    drillThrough: InsightDrillThrough | null }[];
  rates: Record<string, { numerator: number; denominator: number; unknownCount: number;
    valueBasisPoints: number | null; status: string }>;
  moneyByCurrency: { currency: string; capturedAmountMinor: number;
    refundedAmountMinor: number; disputedAmountMinor: number }[];
  chargebackPaymentCount: number; warningCode: string | null; claimBoundary: string };

export type WorkspaceReviewInsights = { range: { from: string; to: string }; sourceAsOf: string;
  backlog: { total: number; unassigned: number; assigned: number; withinSla: number; breached: number };
  assignments: number; releases: number; resolutions: number; conflictedAttempts: number | null;
  conflictedAttemptsStatus: string; authority: string;
  timeToAssignment: { eligibleCount: number; totalMilliseconds: number;
    minimumMilliseconds: number | null; maximumMilliseconds: number | null; missingCount: number };
  timeToResolution: { eligibleCount: number; totalMilliseconds: number;
    minimumMilliseconds: number | null; maximumMilliseconds: number | null; missingCount: number } };

export type PaymentSavedView = { id: string; ownerOperatorId: string; name: string;
  visibility: "PRIVATE" | "MERCHANT_SHARED"; filterSchemaVersion: string;
  filter: { fraudActions: string[] }; sort: string; visibleColumns: string[];
  definitionDigest: string; lifecycleStatus: "ACTIVE" | "DISABLED"; lifecycleVersion: number;
  createdAt: string; updatedAt: string; disabledAt: string | null };

export type BulkAction = { id: string; action: string; itemCount: number; lifecycleStatus: string;
  targetOperatorId: string | null; requestDigest: string; succeededCount: number;
  failedCount: number; createdAt: string; completedAt: string | null };

export type BulkItemPage = { data: { id: string; reviewCaseId: string;
  expectedAssignmentVersion: number; ordinal: number; lifecycleStatus: string;
  failureCode: string | null; observedAssignmentVersion: number | null;
  finalAssignmentVersion: number | null }[]; nextCursor: string | null };

export type ApiVersionCatalogue = { defaultVersion: string; versions: {
  version: string; introducedAt: string; status: string; retirementDate: string | null;
  compatibility: string }[] };

export type PublicErrorCatalogue = { errors: { code: string; httpStatus: number;
  retryable: boolean; idempotencyGuidance: string; recoveryAction: string }[] };

export type OperatorSession = {
  id: string; operatorCode: string; role: "VIEWER" | "ANALYST" | "RISK_MANAGER";
};

export type MonitoringMetric = {
  metricCode: string; definitionVersion: string; windowStart: string; windowEnd: string;
  asOf: string; numerator: number; denominator: number | null; value: number | null;
  unit: "RATIO" | "COUNT";
  coverageStatus: "COMPLETE" | "NO_ELIGIBLE_DATA" | "PARTIAL_LABEL_COVERAGE"
    | "CONTRADICTORY_LABELS" | "INSUFFICIENT_HISTORY" | "ZERO_BASELINE";
  observedCount: number; eligibleCount: number;
  trafficOriginPolicy: "MERCHANT_API_ONLY" | "INCLUDE_SYNTHETIC"; explanation: string;
};

export type MonitoringSummary = {
  definitionVersion: string; taxonomyVersion: string; windowStart: string; windowEnd: string;
  asOf: string; trafficOriginPolicy: "MERCHANT_API_ONLY" | "INCLUDE_SYNTHETIC";
  metrics: MonitoringMetric[];
  reviewBacklog: { total: number; underOneHour: number; oneToFourHours: number;
    fourToTwentyFourHours: number; overTwentyFourHours: number };
};

export type MonitoringCohort = {
  metricCode: string; definitionVersion: string; windowStart: string; windowEnd: string;
  asOf: string; trafficOriginPolicy: "MERCHANT_API_ONLY" | "INCLUDE_SYNTHETIC";
  rows: { resourceType: string; resourceId: string; occurredAt: string;
    numeratorMember: boolean; denominatorMember: boolean; evidenceCode: string; count: number }[];
  nextCursor: string | null;
};

export type AnalyticsFreshnessState =
  | "FRESH" | "LAGGING" | "STALE" | "EMPTY" | "PARTIAL" | "UNAVAILABLE";

export type AnalyticsFreshness = {
  generationId: string;
  generationNumber: number;
  sourceCutoff: string | null;
  lastAppliedEventTime: string | null;
  lastProcessingTime: string | null;
  state: AnalyticsFreshnessState;
  eligibleEventCount: number;
  appliedEventCount: number;
  unavailableStreamCount: number;
  trafficOriginPolicy: "ALL" | "LIVE_ONLY" | "SYNTHETIC_ONLY";
};

export type PaymentRiskAnalytics = {
  paymentIntentId: string;
  intentAmountMinor: number | null;
  currency: string | null;
  captureMethod: string | null;
  trafficOrigin: string | null;
  attemptNumber: number | null;
  attemptOutcome: string | null;
  processorCode: string | null;
  fraudAction: string | null;
  riskLevel: string | null;
  riskScoreBasisPoints: number | null;
  reasonCodesJson: string | null;
  rulesetVersion: string | null;
  featureCatalogueVersion: string | null;
  fraudDecisionId: string | null;
  featureSnapshotId: string | null;
  reviewStatus: string | null;
  reviewOutcome: string | null;
  capturedAmountMinor: number;
  refundedAmountMinor: number;
  disputedAmountMinor: number;
  labelValue: string | null;
  fraudType: string | null;
  labelSource: string | null;
  labelAvailableAt: string | null;
  lifecycleStatus: string;
  coverageStatus: "COMPLETE" | "PARTIAL";
  lastStreamVersion: number;
  lastDomainEventId: string;
  lastEventRecordSha256: string;
  lastEventOccurredAt: string;
  lastEventRecordedAt: string;
  lastProcessedAt: string;
};

export type PaymentRiskAnalyticsPage = {
  data: PaymentRiskAnalytics[];
  nextCursor: string | null;
  freshness: AnalyticsFreshness;
};

export type PaymentRiskAnalyticsDetail = {
  data: PaymentRiskAnalytics;
  freshness: AnalyticsFreshness;
};

export type MerchantRiskCurrencySummary = {
  currency: string;
  paymentCount: number;
  attemptCount: number;
  authorizationCount: number;
  declineCount: number;
  reviewCount: number;
  confirmedFraudCount: number;
  chargebackCount: number;
  enumerationSignalCount: number;
  unlabeledCount: number;
  unknownOutcomeCount: number;
  intentAmountMinor: number;
  authorizedAmountMinor: number;
  capturedAmountMinor: number;
  refundedAmountMinor: number;
  disputedAmountMinor: number;
  sourceEventCount: number;
};

export type MerchantRiskSummary = {
  from: string;
  to: string;
  currencies: MerchantRiskCurrencySummary[];
  nextCursor: string | null;
  freshness: AnalyticsFreshness;
};

export type MerchantRiskTimeSeriesPoint = MerchantRiskCurrencySummary & {
  intervalStart: string;
  latestSourceOccurredAt: string;
  latestSourceRecordedAt: string;
  lastProcessedAt: string;
};

export type MerchantRiskTimeSeries = {
  from: string;
  to: string;
  interval: "HOUR" | "DAY";
  points: MerchantRiskTimeSeriesPoint[];
  nextCursor: string | null;
  freshness: AnalyticsFreshness;
};

export type ProjectionAnalyticsStatus = {
  generations: {
    projectionName: string;
    generationId: string;
    generationNumber: number;
    handlerVersion: string;
    buildSource: string;
    lifecycleStatus: string;
    createdAt: string;
    reconciledAt: string | null;
    activatedAt: string | null;
    retiredAt: string | null;
  }[];
  nextCursor: string | null;
  paymentRisk: AnalyticsFreshness;
  merchantRisk: AnalyticsFreshness;
};

export type WebhookVerification = {
  id: string; endpointId: string;
  lifecycleStatus: "PENDING" | "VERIFIED" | "FAILED" | "EXPIRED";
  expiresAt: string; responseStatus: number | null; failureCode: string | null;
  createdAt: string; completedAt: string | null;
};

export type SimulationRun = {
  id: string;
  candidateVersionId: string;
  candidateDigest: string;
  cohortStart: string;
  cohortEnd: string;
  asOf: string;
  labelCutoff: string;
  trafficOriginPolicy: "LIVE_ONLY" | "INCLUDE_SYNTHETIC";
  lifecycleStatus: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELED";
  processedCount: number;
  eligibleCount: number;
  excludedCount: number;
  failureStage: string | null;
  failureCode: string | null;
};

export type SimulationReport = {
  run: SimulationRun;
  reportDigest: string;
  report: {
    population: { processed: number; eligible: number; excluded: number };
    labelledCount: number;
    protectedSuppressionCount: number;
    blockingDataQualityCount: number;
    actionTransitions: Record<string, number>;
    exclusionReasons: Record<string, number>;
  };
  createdAt: string;
  expiresAt: string;
};

export type GovernedRule = {
  id: string; ruleCode: string; displayName: string; versionNumber: number; ruleDigest: string;
  candidateAction: "BLOCK" | "REVIEW" | "REQUEST_3DS";
  lifecycleStatus: "DRAFT" | "SUBMITTED" | "APPROVED" | "ACTIVE" | "RETIRED";
  createdByOperatorId: string; approvedByOperatorId: string | null;
};

export type SafetyException = {
  id: string; exceptionCode: string; versionNumber: number; sourceRuleVersionId: string;
  sourceRuleCode: string; reasonCode: string; validFrom: string; expiresAt: string;
  exceptionDigest: string;
  lifecycleStatus: "DRAFT" | "SUBMITTED" | "APPROVED" | "ACTIVE" | "RETIRED";
};

export type DisputeWorkflow = {
  id: string; chargebackId: string; reasonCode: string; dueAt: string;
  lifecycleStatus: "DRAFT" | "READY" | "SUBMITTED" | "EXPIRED" | "WON" | "LOST";
  readinessStatus: "INCOMPLETE" | "READY"; missingRequirements: string[];
  latestEvidenceVersion: number | null; evidenceDigest: string | null;
  projectionVersion: number;
};

export type WebhookDeadLetter = {
  id: string; webhookEndpointId: string; webhookEventId: string; eventType: string;
  errorCode: string; attemptCount: number; deliveryGeneration: number;
  generationAttemptCount: number; createdAt: string; lastAttemptAt: string | null;
};

export type WebhookRedelivery = {
  requestId: string; deliveryId: string; eventId: string; endpointId: string;
  requestedGeneration: number; reason: "MANUAL_RETRY" | "ENDPOINT_RECOVERED" | "RECEIVER_REPAIRED";
  lifecycleStatus: "SCHEDULED"; createdAt: string;
};

export type DeveloperTestCard = {
  fixtureId: string; cardNumber: string; authorizationStatus: string;
  networkStatus: string; processorOutcomeCode: string; cvcCheck: string;
  avsLine1Check: string; avsPostalCodeCheck: string; threeDsResult: string;
  usage: "SIMULATOR_ONLY";
};

export type DeveloperCatalogue = {
  catalogueVersion: string; processor: string; testCards: DeveloperTestCard[];
  scenarioTypes: ("APPROVED_PAYMENT" | "ISSUER_DECLINE" | "CARD_TESTING_BURST")[];
};

export type DeveloperScenarioStep = {
  stepIndex: number; stepKey: string; operation: string; method: "POST";
  path: string; idempotencyKey: string; body: Record<string, unknown>;
  fixtureId: string | null; expectedOutcomeCode: string | null;
  lifecycleStatus: "PENDING" | "COMPLETED"; resourceType: string;
  resourceId: string | null; completedAt: string | null;
};

export type DeveloperScenarioRun = {
  id: string; merchantId: string;
  scenarioType: "APPROVED_PAYMENT" | "ISSUER_DECLINE" | "CARD_TESTING_BURST";
  catalogueVersion: string; seedDigest: string; specificationDigest: string;
  lifecycleStatus: "CREATED" | "RUNNING" | "SUCCEEDED" | "FAILED";
  totalSteps: number; nextStepIndex: number; steps: DeveloperScenarioStep[];
  nextStep: DeveloperScenarioStep | null; leaseOwner: string | null;
  leaseEpoch: number; leaseExpiresAt: string | null; failureCode: string | null;
  reportDigest: string | null; createdAt: string; updatedAt: string;
  completedAt: string | null;
};

export type DeveloperScenarioReport = {
  runId: string; reportDigest: string;
  report: { catalogueVersion: string; completedAt: string;
    outcomeBreakdown: Record<string, number>; originBreakdown: Record<string, number>; scenarioType: string;
    specificationDigest: string; stepsCompleted: number; trafficOrigin: string };
};

function sameOriginPath(path: string, operation: string): string {
  if (!path.startsWith("/")) {
    throw new Error(`${operation} path must remain on this API origin`);
  }
  let target: URL;
  try {
    target = new URL(path, window.location.origin);
  } catch {
    throw new Error(`${operation} path must remain on this API origin`);
  }
  if (target.origin !== window.location.origin || target.hash) {
    throw new Error(`${operation} path must remain on this API origin`);
  }
  return `${target.pathname}${target.search}`;
}

async function request<T>(credential: string, path: string, init?: RequestInit): Promise<T> {
  const target = sameOriginPath(path, "Risk workspace");
  const response = await fetch(target, {
    ...init,
    credentials: "omit",
    redirect: "error",
    referrerPolicy: "no-referrer",
    headers: {
      "Content-Type": "application/json",
      "X-Risk-Operator-Key": credential,
      "Thaap-Version": "2026-08-01",
      ...init?.headers,
    },
  });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as {
      message?: string;
      requestId?: string;
    };
    const failure = new Error(body.message ?? "The workspace request failed") as ApiFailure;
    failure.status = response.status;
    failure.requestId = body.requestId;
    throw failure;
  }
  return (await response.json()) as T;
}

async function developerRequest<T>(apiKey: string, runnerToken: string,
  path: string, init?: RequestInit): Promise<T> {
  const target = sameOriginPath(path, "Developer scenario");
  const response = await fetch(target, {
    ...init,
    credentials: "omit",
    redirect: "error",
    referrerPolicy: "no-referrer",
    headers: {
      "Content-Type": "application/json",
      "X-Api-Key": apiKey,
      "X-Thaap-Scenario-Runner-Token": runnerToken,
      "Thaap-Version": "2026-08-01",
      ...init?.headers,
    },
  });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { message?: string; requestId?: string };
    const failure = new Error(body.message ?? "The developer scenario request failed") as ApiFailure;
    failure.status = response.status;
    failure.requestId = body.requestId;
    throw failure;
  }
  return (await response.json()) as T;
}

async function merchantRequest<T>(apiKey: string, path: string, init?: RequestInit): Promise<T> {
  const target = sameOriginPath(path, "Merchant operation");
  const response = await fetch(target, {
    ...init, credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
    headers: { "Content-Type": "application/json", "X-Api-Key": apiKey,
      "Thaap-Version": "2026-08-01", ...init?.headers },
  });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { message?: string; requestId?: string };
    const failure = new Error(body.message ?? "The merchant operation failed") as ApiFailure;
    failure.status = response.status;
    failure.requestId = body.requestId;
    throw failure;
  }
  return (await response.json()) as T;
}

async function publicDeveloperRequest<T>(path: string): Promise<T> {
  const target = sameOriginPath(path, "Developer portal");
  const response = await fetch(target, {
    credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
    headers: { "Accept": "application/json", "Thaap-Version": "2026-08-01" },
  });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { message?: string; requestId?: string };
    const failure = new Error(body.message ?? "The developer contract is unavailable") as ApiFailure;
    failure.status = response.status;
    failure.requestId = body.requestId;
    throw failure;
  }
  return (await response.json()) as T;
}

export function getOperatorSession(credential: string): Promise<OperatorSession> {
  return request(credential, "/risk/operators/me");
}

export function getMonitoringSummary(credential: string,
  includeSynthetic: boolean): Promise<MonitoringSummary> {
  const policy = includeSynthetic ? "INCLUDE_SYNTHETIC" : "MERCHANT_API_ONLY";
  return request(credential, `/risk/monitoring/summary?trafficOriginPolicy=${policy}`);
}

export function getMonitoringCohort(credential: string, metricCode: string,
  summary: MonitoringSummary): Promise<MonitoringCohort> {
  const parameters = new URLSearchParams({
    windowStart: summary.windowStart, windowEnd: summary.windowEnd, asOf: summary.asOf,
    trafficOriginPolicy: summary.trafficOriginPolicy, limit: "50",
  });
  return request(credential,
    `/risk/monitoring/metrics/${encodeURIComponent(metricCode)}/cohort?${parameters}`);
}

export function getPaymentRiskAnalytics(credential: string, originCursor?: string):
  Promise<PaymentRiskAnalyticsPage> {
  const parameters = new URLSearchParams({ limit: "50" });
  if (originCursor) parameters.set("cursor", originCursor);
  return request(credential, `/risk/analytics/payments?${parameters}`);
}

export function getPaymentRiskAnalyticsDetail(credential: string,
  paymentIntentId: string): Promise<PaymentRiskAnalyticsDetail> {
  return request(credential,
    `/risk/analytics/payments/${encodeURIComponent(paymentIntentId)}`);
}

export function getMerchantRiskAnalyticsSummary(credential: string,
  originPolicy: AnalyticsFreshness["trafficOriginPolicy"], cursor?: string):
  Promise<MerchantRiskSummary> {
  const parameters = new URLSearchParams({ originPolicy, limit: "100" });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential, `/risk/analytics/merchant/summary?${parameters}`);
}

export function getMerchantRiskAnalyticsTimeSeries(credential: string,
  originPolicy: AnalyticsFreshness["trafficOriginPolicy"], cursor?: string):
  Promise<MerchantRiskTimeSeries> {
  const parameters = new URLSearchParams({
    interval: "HOUR", originPolicy, limit: "100",
  });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential, `/risk/analytics/merchant/timeseries?${parameters}`);
}

export function getProjectionAnalyticsStatus(credential: string, cursor?: string):
  Promise<ProjectionAnalyticsStatus> {
  const parameters = new URLSearchParams({ limit: "100" });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential, `/risk/analytics/projections?${parameters}`);
}

export function verifyWebhookEndpoint(apiKey: string, endpointId: string,
  idempotencyKey: string): Promise<WebhookVerification> {
  return merchantRequest(apiKey, `/webhook_endpoints/${encodeURIComponent(endpointId)}/verification_challenges`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: "{}",
  });
}

export function searchQueue(
  credential: string,
  status: string,
  slaState: string,
  cursor?: string,
): Promise<QueuePage> {
  const parameters = new URLSearchParams({ limit: "25" });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential, `/risk/workspace/reviews/search?${parameters}`, {
    method: "POST",
    body: JSON.stringify({
      filter: {
        statuses: status ? [status] : [],
        slaStates: slaState ? [slaState] : [],
      },
      sort: "CREATED_AT_ASC",
    }),
  });
}

export function listSavedSearches(credential: string): Promise<SavedSearch[]> {
  return request(credential, "/risk/workspace/saved-searches");
}

export function saveSearch(
  credential: string,
  name: string,
  status: string,
  slaState: string, idempotencyKey = crypto.randomUUID(),
): Promise<SavedSearch> {
  return request(credential, "/risk/workspace/saved-searches", {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({
      name,
      visibility: "PRIVATE",
      filter: {
        statuses: status ? [status] : [],
        slaStates: slaState ? [slaState] : [],
      },
      sort: "CREATED_AT_ASC",
      visibleColumns: ["CREATED_AT", "STATUS", "SLA_STATE", "DECISION_ACTION"],
    }),
  });
}

export function executeSavedSearch(
  credential: string,
  id: string,
): Promise<QueuePage> {
  return request(credential, `/risk/workspace/saved-searches/${id}/execute`, { method: "POST" });
}

export function listNotes(
  credential: string,
  reviewId: string,
  assignmentVersion: number,
): Promise<ReviewNote[]> {
  return request(
    credential,
    `/risk/workspace/reviews/${reviewId}/notes?expectedAssignmentVersion=${assignmentVersion}`,
  );
}

export function createNote(
  credential: string,
  reviewId: string,
  assignmentVersion: number,
  content: string,
  supersedesNoteId?: string,
  idempotencyKey = crypto.randomUUID(),
): Promise<ReviewNote> {
  return request(credential, `/risk/workspace/reviews/${reviewId}/notes`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ expectedAssignmentVersion: assignmentVersion, content, supersedesNoteId }),
  });
}

export function redactNote(
  credential: string,
  reviewId: string,
  noteId: string,
  idempotencyKey = crypto.randomUUID(),
): Promise<ReviewNote> {
  return request(credential, `/risk/workspace/reviews/${reviewId}/notes/${noteId}/redact`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: "{}",
  });
}

export function createSimulation(
  credential: string,
  candidateVersionId: string,
  cohortStart: string,
  cohortEnd: string,
  asOf: string,
  includeSynthetic: boolean,
  idempotencyKey = crypto.randomUUID(),
): Promise<SimulationRun> {
  return request(credential, "/risk/simulations", {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({
      candidateType: "MERCHANT_RISK_POLICY",
      candidateVersionId,
      cohortStart,
      cohortEnd,
      asOf,
      labelCutoff: asOf,
      trafficOriginPolicy: includeSynthetic ? "INCLUDE_SYNTHETIC" : "LIVE_ONLY",
    }),
  });
}

export function getSimulation(credential: string, runId: string): Promise<SimulationRun> {
  return request(credential, `/risk/simulations/${runId}`);
}

export function getSimulationReport(
  credential: string,
  runId: string,
): Promise<SimulationReport> {
  return request(credential, `/risk/simulations/${runId}/report`);
}

export function listGovernedRules(credential: string): Promise<GovernedRule[]> {
  return request(credential, "/risk/controls/rules");
}

export function listSafetyExceptions(credential: string): Promise<SafetyException[]> {
  return request(credential, "/risk/controls/exceptions");
}

export function transitionControl<T>(credential: string, family: "rules" | "exceptions",
  id: string, transition: string, simulationRunId?: string,
  idempotencyKey = crypto.randomUUID()): Promise<T> {
  return request(credential, `/risk/controls/${family}/${id}/${transition}`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(simulationRunId ? { simulationRunId } : {}),
  });
}

export function listDisputes(credential: string): Promise<DisputeWorkflow[]> {
  return request(credential, "/risk/disputes?limit=25");
}

export function reviseDisputeEvidence(credential: string, chargebackId: string,
  deliveryConfirmed: boolean, customerCommunicationPresent: boolean,
  deviceContinuity: boolean, idempotencyKey = crypto.randomUUID()): Promise<DisputeWorkflow> {
  return request(credential, `/risk/disputes/${chargebackId}/evidence-versions`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ deliveryConfirmed, customerCommunicationPresent, deviceContinuity }),
  });
}

export function submitDispute(credential: string, chargebackId: string,
  expectedEvidenceVersion: number, idempotencyKey = crypto.randomUUID()): Promise<DisputeWorkflow> {
  return request(credential, `/risk/disputes/${chargebackId}/submit`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ expectedEvidenceVersion }),
  });
}

export function listWebhookDeadLetters(credential: string): Promise<WebhookDeadLetter[]> {
  return request(credential, "/risk/webhook-dead-letters?limit=25");
}

export function redeliverWebhook(credential: string,
  deliveryId: string, idempotencyKey = crypto.randomUUID()): Promise<WebhookRedelivery> {
  return request(credential, `/risk/webhook-deliveries/${deliveryId}/redeliver`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ reason: "RECEIVER_REPAIRED" }),
  });
}

export function searchInvestigations(credential: string, fraudAction = "",
  cursor?: string): Promise<InvestigationSearchPage> {
  const parameters = new URLSearchParams({ limit: "25" });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential, `/risk/workspace/payments/search?${parameters}`, {
    method: "POST",
    body: JSON.stringify({ filter: { fraudActions: fraudAction ? [fraudAction] : [] },
      sort: "CREATED_AT_DESC" }),
  });
}

export function getInvestigation(credential: string, paymentIntentId: string):
  Promise<InvestigationDetail> {
  return request(credential, `/risk/workspace/payments/${encodeURIComponent(paymentIntentId)}`);
}

export function getInvestigationTimeline(credential: string, paymentIntentId: string,
  cursor?: string): Promise<TimelinePage> {
  const parameters = new URLSearchParams({ limit: "25" });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential,
    `/risk/workspace/payments/${encodeURIComponent(paymentIntentId)}/timeline?${parameters}`);
}

export function getRelatedPayments(credential: string, paymentIntentId: string,
  cursor?: string): Promise<RelatedPaymentPage> {
  const parameters = new URLSearchParams({ limit: "25" });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential,
    `/risk/workspace/payments/${encodeURIComponent(paymentIntentId)}/related?${parameters}`);
}

export function getWorkspaceDecisionInsights(credential: string):
  Promise<WorkspaceDecisionInsights> {
  return request(credential, "/risk/workspace/insights/decision-outcomes?originPolicy=ALL");
}

export function getWorkspaceReviewInsights(credential: string):
  Promise<WorkspaceReviewInsights> {
  return request(credential, "/risk/workspace/insights/review-operations");
}

export function listPaymentSavedViews(credential: string): Promise<PaymentSavedView[]> {
  return request(credential, "/risk/workspace/payment-views");
}

export function createPaymentSavedView(credential: string, name: string,
  visibility: "PRIVATE" | "MERCHANT_SHARED", fraudAction: string,
  idempotencyKey = crypto.randomUUID()): Promise<PaymentSavedView> {
  return request(credential, "/risk/workspace/payment-views", {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ name, visibility,
      filter: { fraudActions: fraudAction ? [fraudAction] : [] }, sort: "CREATED_AT_DESC" }),
  });
}

export function disablePaymentSavedView(credential: string, viewId: string,
  expectedVersion: number, idempotencyKey = crypto.randomUUID()): Promise<PaymentSavedView> {
  return request(credential,
    `/risk/workspace/payment-views/${encodeURIComponent(viewId)}/disable`, {
      method: "POST", headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({ expectedVersion }),
    });
}

export function executePaymentSavedView(credential: string, viewId: string,
  cursor?: string): Promise<InvestigationSearchPage> {
  const parameters = new URLSearchParams({ limit: "25" });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential,
    `/risk/workspace/payment-views/${encodeURIComponent(viewId)}/execute?${parameters}`,
    { method: "POST" });
}

export function startBulkAssignment(credential: string, action: string,
  targetOperatorId: string | null, items: { reviewCaseId: string;
    expectedAssignmentVersion: number }[], idempotencyKey = crypto.randomUUID()):
  Promise<BulkAction> {
  return request(credential, "/risk/workspace/review-bulk-actions", {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ action, targetOperatorId, items }),
  });
}

export function getBulkAssignment(credential: string, actionId: string): Promise<BulkAction> {
  return request(credential,
    `/risk/workspace/review-bulk-actions/${encodeURIComponent(actionId)}`);
}

export function getBulkAssignmentItems(credential: string, actionId: string,
  cursor?: string): Promise<BulkItemPage> {
  const parameters = new URLSearchParams({ limit: "25" });
  if (cursor) parameters.set("cursor", cursor);
  return request(credential,
    `/risk/workspace/review-bulk-actions/${encodeURIComponent(actionId)}/items?${parameters}`);
}

export async function loadDeveloperCatalogue(): Promise<DeveloperCatalogue> {
  const response = await fetch("/developer/test-cards", {
    credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
    headers: { "Accept": "application/json", "Thaap-Version": "2026-08-01" },
  });
  if (!response.ok) throw new Error("The simulator catalogue is unavailable");
  return (await response.json()) as DeveloperCatalogue;
}

export function getApiVersionCatalogue(): Promise<ApiVersionCatalogue> {
  return publicDeveloperRequest("/developer/api-versions");
}

export function getPublicErrorCatalogue(): Promise<PublicErrorCatalogue> {
  return publicDeveloperRequest("/developer/error-catalog");
}

export function createDeveloperScenario(apiKey: string, runnerToken: string,
  scenarioType: DeveloperScenarioRun["scenarioType"], seed: string,
  idempotencyKey = crypto.randomUUID()): Promise<DeveloperScenarioRun> {
  return developerRequest(apiKey, runnerToken, "/developer/scenario-runs", {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ scenarioType, seed }),
  });
}

export function claimDeveloperScenario(apiKey: string, runnerToken: string,
  runId: string, workerId: string, idempotencyKey: string): Promise<DeveloperScenarioRun> {
  return developerRequest(apiKey, runnerToken, `/developer/scenario-runs/${runId}/claims`, {
    method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ workerId }),
  });
}

export async function executeDeveloperScenarioStep(apiKey: string, runnerToken: string,
  step: DeveloperScenarioStep): Promise<string> {
  const allowedPaths: Record<string, RegExp> = {
    CREATE_CUSTOMER: /^\/customers$/,
    CREATE_PAYMENT_METHOD: /^\/payment_methods$/,
    CREATE_PAYMENT_INTENT: /^\/payment_intents$/,
    CONFIRM_PAYMENT_INTENT:
      /^\/payment_intents\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/confirm$/i,
  };
  const canonicalPath = sameOriginPath(step.path, "Developer scenario");
  if (!allowedPaths[step.operation]?.test(canonicalPath) || step.method !== "POST") {
    throw new Error("The scenario step is not in the governed operation catalogue");
  }
  const response = await developerRequest<{ id: string }>(apiKey, runnerToken, canonicalPath, {
    method: "POST", headers: { "Idempotency-Key": step.idempotencyKey },
    body: JSON.stringify(step.body),
  });
  return response.id;
}

export function completeDeveloperScenarioStep(apiKey: string, runnerToken: string,
  runId: string, stepIndex: number, workerId: string, leaseEpoch: number,
  resourceId: string, idempotencyKey: string): Promise<DeveloperScenarioRun> {
  return developerRequest(apiKey, runnerToken,
    `/developer/scenario-runs/${runId}/steps/${stepIndex}/complete`, {
      method: "POST", headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({ workerId, leaseEpoch, resourceId }),
    });
}

export function getDeveloperScenarioReport(apiKey: string, runnerToken: string,
  runId: string): Promise<DeveloperScenarioReport> {
  return developerRequest(apiKey, runnerToken, `/developer/scenario-runs/${runId}/report`);
}
