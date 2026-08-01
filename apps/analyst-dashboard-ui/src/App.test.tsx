import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./App";
import type { MonitoringMetric, MonitoringSummary } from "./api";

const managerSession = { id: "90000000-0000-0000-0000-000000000009",
  operatorCode: "risk-manager", role: "RISK_MANAGER" };
const emptyMonitoring: MonitoringSummary = { definitionVersion: "merchant_risk_monitoring_v1",
  taxonomyVersion: "fraud_taxonomy_v1", windowStart: "2026-07-26T00:00:00Z",
  windowEnd: "2026-07-27T00:00:00Z", asOf: "2026-07-27T00:00:00Z",
  trafficOriginPolicy: "MERCHANT_API_ONLY", metrics: [], reviewBacklog: {
    total: 0, underOneHour: 0, oneToFourHours: 0, fourToTwentyFourHours: 0, overTwentyFourHours: 0,
  } };

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
}

function enqueueConnect(fetchMock: ReturnType<typeof vi.fn>, session = managerSession,
  monitoring = emptyMonitoring, disputes: unknown[] = []) {
  return fetchMock
    .mockResolvedValueOnce(new Response(JSON.stringify(session), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(monitoring), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ asOf: monitoring.asOf, rows: [], nextCursor: null }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(disputes), { status: 200 }));
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

test("keeps credential entry explicit and clears it on disconnect", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(managerSession), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyMonitoring), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ asOf: "2026-07-25T00:00:00Z", rows: [], nextCursor: null }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();

  const credential = screen.getByLabelText("Risk operator credential");
  expect(credential).toHaveAttribute("type", "password");
  expect(credential).toHaveAttribute("autocomplete", "off");
  await user.type(credential, "thaap-risk-secret");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));
  expect(await screen.findByRole("heading", { name: "Reviews" })).toBeVisible();
  expect(fetchMock.mock.calls[0][1]).toMatchObject({
    credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
  });
  await user.click(screen.getByRole("button", { name: "Disconnect" }));
  expect(screen.getByLabelText("Risk operator credential")).toHaveValue("");
});

test("keeps monitoring and reviews usable when saved searches return a dependency failure", async () => {
  const review = {
    id: "91000000-0000-0000-0000-000000000009",
    paymentIntentId: "92000000-0000-0000-0000-000000000009",
    decisionAction: "REVIEW", reasonCodes: ["VELOCITY_THRESHOLD"], status: "OPEN",
    reviewStage: "TRIAGE", assignedOperatorId: null, assignmentVersion: 0,
    slaState: "WITHIN_SLA", createdAt: emptyMonitoring.asOf,
  };
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(managerSession), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyMonitoring), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      asOf: emptyMonitoring.asOf, rows: [review], nextCursor: null,
    }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      message: "Saved-search storage is temporarily unavailable", requestId: "saved-search-503",
    }), { status: 503 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();

  await user.type(screen.getByLabelText("Risk operator credential"), "degraded-memory-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));

  expect(await screen.findByRole("heading", { name: "Reviews" })).toBeVisible();
  expect(screen.getByRole("heading", { name: "Merchant risk health" })).toBeVisible();
  expect(screen.getByRole("button", { name: `Open review ${review.id}` })).toBeVisible();
  expect(screen.getByText(/Saved searches unavailable/)).toBeVisible();
  const sliceAlert = screen.getByText(/saved-search-503/).closest("[role='alert']");
  expect(sliceAlert).toHaveAttribute("data-failure-state", "dependency");
  expect(screen.getByLabelText("Signed-in operator role")).toHaveTextContent("RISK MANAGER");
  expect(document.body).not.toHaveTextContent("degraded-memory-key");

  await user.click(screen.getByRole("button", { name: "Retry saved searches" }));
  expect(await screen.findByText("Saved searches restored.")).toBeVisible();
  expect(screen.queryByText(/Saved searches unavailable/)).not.toBeInTheDocument();
  expect(fetchMock.mock.calls[7][0]).toBe("/risk/workspace/saved-searches");
});

test("keeps successful workspace slices available when disputes are denied", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(managerSession), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyMonitoring), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      asOf: emptyMonitoring.asOf, rows: [], nextCursor: null,
    }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      message: "Dispute scope is not granted", requestId: "dispute-403",
    }), { status: 403 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();

  await user.type(screen.getByLabelText("Risk operator credential"), "denied-slice-memory-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));

  expect(await screen.findByRole("heading", { name: "Reviews" })).toBeVisible();
  expect(screen.getByRole("heading", { name: "Merchant risk health" })).toBeVisible();
  expect(screen.getByText(/Disputes unavailable/)).toBeVisible();
  const sliceAlert = screen.getByText(/dispute-403/).closest("[role='alert']");
  expect(sliceAlert).toHaveAttribute("data-failure-state", "denied");
  expect(screen.queryByRole("button", { name: "Retry disputes" })).not.toBeInTheDocument();
  expect(document.body).not.toHaveTextContent("denied-slice-memory-key");
});

test("renders an accessible error status without echoing credentials", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(
    new Response(JSON.stringify({
      message: "Risk operator credential is invalid", requestId: "request-safe-1",
    }), { status: 401 }),
  )));
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "never-render-this-secret");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));
  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent("request-safe-1");
  expect(alert).toHaveAttribute("data-failure-state", "authentication");
  expect(document.body).not.toHaveTextContent("never-render-this-secret");
});

test("entry screen has no automated semantic accessibility violations", async () => {
  renderApp();
  const results = await axe.run(document.body, {
    rules: { "color-contrast": { enabled: false } },
  });
  expect(results.violations).toEqual([]);
});

test("creates a simulation and renders only durable report evidence after success", async () => {
  const run = {
    id: "10000000-0000-0000-0000-000000000001",
    candidateVersionId: "20000000-0000-0000-0000-000000000002",
    candidateDigest: "a".repeat(64),
    cohortStart: "2026-07-24T00:00:00Z",
    cohortEnd: "2026-07-25T00:00:00Z",
    asOf: "2026-07-25T00:00:00Z",
    labelCutoff: "2026-07-25T00:00:00Z",
    trafficOriginPolicy: "LIVE_ONLY",
    lifecycleStatus: "QUEUED",
    processedCount: 0,
    eligibleCount: 0,
    excludedCount: 0,
    failureStage: null,
    failureCode: null,
  };
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(managerSession), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyMonitoring), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ asOf: run.asOf, rows: [], nextCursor: null }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(run), { status: 201 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ ...run, lifecycleStatus: "SUCCEEDED", processedCount: 1, eligibleCount: 1 }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      run: { ...run, lifecycleStatus: "SUCCEEDED", processedCount: 1, eligibleCount: 1 },
      reportDigest: "b".repeat(64),
      report: { population: { processed: 1, eligible: 1, excluded: 0 }, labelledCount: 0,
        protectedSuppressionCount: 0, blockingDataQualityCount: 0,
        actionTransitions: { "ALLOW->REVIEW": 1 }, exclusionReasons: {} },
      createdAt: run.asOf, expiresAt: "2026-07-26T00:00:00Z",
    }), { status: 200 })));
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "memory-only-manager-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));
  await screen.findByRole("heading", { name: "Historical policy evidence" });

  await user.type(screen.getByLabelText("Candidate policy version ID"), run.candidateVersionId);
  await user.type(screen.getByLabelText("Cohort start (UTC ISO-8601)"), run.cohortStart);
  await user.type(screen.getByLabelText("Cohort end (UTC ISO-8601)"), run.cohortEnd);
  await user.click(screen.getByRole("button", { name: "Queue simulation" }));
  expect(await screen.findByText("QUEUED")).toBeVisible();
  expect(document.body).not.toHaveTextContent("ALLOW->REVIEW");

  await user.click(screen.getByRole("button", { name: "Refresh durable progress" }));
  expect(await screen.findByText(/ALLOW->REVIEW 1/)).toBeVisible();
  expect(document.body).not.toHaveTextContent("memory-only-manager-key");
});

test("renders dispute deadline readiness and exact submitted version action", async () => {
  const dispute = {
    id: "30000000-0000-0000-0000-000000000003",
    chargebackId: "40000000-0000-0000-0000-000000000004",
    reasonCode: "PRODUCT_NOT_RECEIVED",
    dueAt: "2026-08-10T00:00:00Z",
    lifecycleStatus: "READY",
    readinessStatus: "READY",
    missingRequirements: [],
    latestEvidenceVersion: 3,
    evidenceDigest: "c".repeat(64),
    projectionVersion: 3,
  };
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(managerSession), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyMonitoring), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ asOf: dispute.dueAt, rows: [], nextCursor: null }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([dispute]), { status: 200 })));
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "memory-only-manager-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));

  expect(await screen.findByRole("heading", { name: "Deadline and outcome operations" })).toBeVisible();
  expect(screen.getByText("All governed requirements satisfied.")).toBeVisible();
  expect(screen.getByRole("button", { name: "Submit version 3" })).toBeEnabled();
  expect(document.body).not.toHaveTextContent("memory-only-manager-key");
});

test("developer console keeps both credentials in memory and renders durable scenario progress", async () => {
  const run = {
    id: "50000000-0000-0000-0000-000000000005",
    merchantId: "60000000-0000-0000-0000-000000000006",
    scenarioType: "APPROVED_PAYMENT",
    catalogueVersion: "developer_scenarios_v1",
    seedDigest: "d".repeat(64), specificationDigest: "e".repeat(64),
    lifecycleStatus: "CREATED", totalSteps: 4, nextStepIndex: 0,
    steps: [],
    nextStep: { stepIndex: 0, stepKey: "customer", operation: "CREATE_CUSTOMER",
      method: "POST", path: "/customers", idempotencyKey: "scenario-safe-key",
      body: { email: "scenario-safe@example.test", phone: "+15550000000" },
      fixtureId: null, expectedOutcomeCode: null, lifecycleStatus: "PENDING",
      resourceType: "CUSTOMER", resourceId: null, completedAt: null },
    leaseOwner: null, leaseEpoch: 0, leaseExpiresAt: null, failureCode: null,
    reportDigest: null, createdAt: "2026-07-27T00:00:00Z",
    updatedAt: "2026-07-27T00:00:00Z", completedAt: null,
  };
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({
      catalogueVersion: "developer_scenarios_v1", processor: "THAAP_SIMULATOR",
      scenarioTypes: ["APPROVED_PAYMENT", "ISSUER_DECLINE", "CARD_TESTING_BURST"],
      testCards: [{ fixtureId: "approved", cardNumber: "4999990000000001",
        authorizationStatus: "APPROVED", networkStatus: "APPROVED",
        processorOutcomeCode: "APPROVED", cvcCheck: "PASS", avsLine1Check: "PASS",
        avsPostalCodeCheck: "PASS", threeDsResult: "NOT_REQUIRED", usage: "SIMULATOR_ONLY" }],
    }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(run), { status: 201 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();

  await user.click(screen.getByRole("button", { name: "Load test-card catalogue" }));
  expect(await screen.findByText("4999990000000001")).toBeVisible();
  await user.type(screen.getByLabelText("Merchant API key"), "merchant-memory-secret");
  await user.type(screen.getByLabelText("Scenario runner credential"), "runner-memory-secret");
  await user.click(screen.getByRole("button", { name: "Create or resume" }));

  expect(await screen.findByText("0 / 4 verified steps")).toBeVisible();
  expect(screen.getByText("Next: CREATE CUSTOMER")).toBeVisible();
  expect(document.body).not.toHaveTextContent("merchant-memory-secret");
  expect(document.body).not.toHaveTextContent("runner-memory-secret");
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
  expect(fetchMock.mock.calls[1][1]).toMatchObject({ credentials: "omit", redirect: "error" });
});

test("renders durable webhook dead-letter state and refreshes after one redelivery", async () => {
  const deadLetter = {
    id: "70000000-0000-0000-0000-000000000007",
    webhookEndpointId: "71000000-0000-0000-0000-000000000007",
    webhookEventId: "72000000-0000-0000-0000-000000000007",
    eventType: "refund.created", errorCode: "TRANSPORT_ERROR",
    attemptCount: 5, deliveryGeneration: 1, generationAttemptCount: 5,
    createdAt: "2026-07-27T00:00:00Z", lastAttemptAt: "2026-07-27T00:01:00Z",
  };
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(managerSession), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyMonitoring), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ asOf: deadLetter.createdAt, rows: [], nextCursor: null }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([deadLetter]), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({
      requestId: "73000000-0000-0000-0000-000000000007", deliveryId: deadLetter.id,
      eventId: deadLetter.webhookEventId, endpointId: deadLetter.webhookEndpointId,
      requestedGeneration: 2, reason: "RECEIVER_REPAIRED", lifecycleStatus: "SCHEDULED",
      createdAt: "2026-07-27T00:02:00Z",
    }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "memory-only-analyst-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));

  await user.click(await screen.findByRole("button", { name: "Refresh dead letters" }));
  expect(await screen.findByText("refund.created")).toBeVisible();
  expect(screen.getByText(/TRANSPORT ERROR/)).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Redeliver exact event" }));

  expect(await screen.findByText("No exhausted webhook deliveries.")).toBeVisible();
  expect(document.body).not.toHaveTextContent("memory-only-analyst-key");
  expect(fetchMock.mock.calls[8][1]).toMatchObject({ method: "POST" });
});

test("renders partial monitoring truth and drills into the exact cohort window", async () => {
  const metric: MonitoringMetric = { metricCode: "AUTHORIZATION_RATE", definitionVersion: "merchant_risk_monitoring_v1",
    windowStart: emptyMonitoring.windowStart, windowEnd: emptyMonitoring.windowEnd,
    asOf: emptyMonitoring.asOf, numerator: 2, denominator: 4, value: 50,
    unit: "RATIO", coverageStatus: "PARTIAL_LABEL_COVERAGE", observedCount: 2, eligibleCount: 4,
    trafficOriginPolicy: "MERCHANT_API_ONLY", explanation: "Two of four attempts have complete evidence." };
  const monitoring: MonitoringSummary = { ...emptyMonitoring, metrics: [{ ...metric,
    coverageStatus: "PARTIAL_LABEL_COVERAGE", trafficOriginPolicy: "MERCHANT_API_ONLY" }] };
  const cohort = { metricCode: metric.metricCode, definitionVersion: metric.definitionVersion,
    windowStart: metric.windowStart, windowEnd: metric.windowEnd, asOf: metric.asOf,
    trafficOriginPolicy: "MERCHANT_API_ONLY", rows: [{ resourceType: "PAYMENT_ATTEMPT",
      resourceId: "a0000000-0000-0000-0000-000000000001", occurredAt: metric.windowStart,
      numeratorMember: true, denominatorMember: true, evidenceCode: "AUTHORIZED", count: 1 }],
    nextCursor: null };
  const fetchMock = enqueueConnect(vi.fn(), managerSession, monitoring)
    .mockResolvedValueOnce(new Response(JSON.stringify(cohort), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "monitoring-memory-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));

  expect(await screen.findByText("PARTIAL_LABEL_COVERAGE")).toBeVisible();
  expect(screen.getByText("2 observed · 4 eligible")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Inspect cohort" }));
  expect(await screen.findByText("AUTHORIZED")).toBeVisible();
  expect(fetchMock.mock.calls[7][0]).toContain("windowStart=2026-07-26T00%3A00%3A00Z");
  expect(document.body).not.toHaveTextContent("monitoring-memory-key");
});

test("uses server-derived viewer role to hide every mutating risk control", async () => {
  const viewer = { ...managerSession, role: "VIEWER", operatorCode: "risk-viewer" };
  const readOnlyDispute = {
    id: "d0000000-0000-0000-0000-000000000001",
    chargebackId: "d0000000-0000-0000-0000-000000000002",
    reasonCode: "FRAUDULENT", dueAt: "2026-08-10T00:00:00Z",
    lifecycleStatus: "READY", readinessStatus: "READY", missingRequirements: [],
    latestEvidenceVersion: 2, evidenceDigest: "f".repeat(64), projectionVersion: 2,
  };
  const fetchMock = enqueueConnect(vi.fn(), viewer, emptyMonitoring, [readOnlyDispute]);
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "viewer-memory-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));

  expect(await screen.findByLabelText("Signed-in operator role")).toHaveTextContent("VIEWER");
  expect(screen.queryByRole("button", { name: "Queue simulation" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Refresh dead letters" })).not.toBeInTheDocument();
  expect(screen.getByText("Historical simulation mutations require risk-manager access.")).toBeVisible();
  expect(screen.getByText("FRAUDULENT")).toBeVisible();
  expect(screen.queryByRole("button", { name: "Record closed evidence facts" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Submit version 2" })).not.toBeInTheDocument();
});

test("retries a stale mutation with the identical idempotency key", async () => {
  const saved = { id: "b0000000-0000-0000-0000-000000000001", name: "Priority",
    visibility: "PRIVATE", lifecycleVersion: 1 };
  const fetchMock = enqueueConnect(vi.fn())
    .mockResolvedValueOnce(new Response(JSON.stringify({ message: "Saved-search version is stale",
      requestId: "stale-request" }), { status: 409 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(saved), { status: 201 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([saved]), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "retry-memory-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));
  await user.type(await screen.findByLabelText("Save current view"), "Priority");
  await user.click(screen.getByRole("button", { name: "Save privately" }));

  const alert = await screen.findByRole("alert");
  expect(alert).toHaveAttribute("data-failure-state", "stale");
  await user.click(screen.getByRole("button", { name: "Retry same safe request" }));
  expect(await screen.findByText("PRIVATE")).toBeVisible();
  const firstHeaders = fetchMock.mock.calls[7][1]?.headers as Record<string, string>;
  const retryHeaders = fetchMock.mock.calls[8][1]?.headers as Record<string, string>;
  expect(retryHeaders["Idempotency-Key"]).toBe(firstHeaders["Idempotency-Key"]);
});

test("verifies a webhook endpoint with merchant authority and no runner credential", async () => {
  const challenge = { id: "c0000000-0000-0000-0000-000000000001",
    endpointId: "c0000000-0000-0000-0000-000000000002", lifecycleStatus: "VERIFIED",
    expiresAt: "2026-07-27T01:00:00Z", responseStatus: 200, failureCode: null,
    createdAt: "2026-07-27T00:00:00Z", completedAt: "2026-07-27T00:00:01Z" };
  const fetchMock = vi.fn().mockResolvedValueOnce(
    new Response(JSON.stringify(challenge), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Merchant API key"), "merchant-verification-key");
  await user.type(screen.getByLabelText("Webhook endpoint ID"), challenge.endpointId);
  await user.click(screen.getByRole("button", { name: "Start verification" }));

  expect(await screen.findByText("VERIFIED")).toBeVisible();
  const headers = fetchMock.mock.calls[0][1]?.headers as Record<string, string>;
  expect(headers["X-Api-Key"]).toBe("merchant-verification-key");
  expect(headers["X-Thaap-Scenario-Runner-Token"]).toBeUndefined();
  expect(headers["Idempotency-Key"]).toBeTruthy();
  expect(document.body).not.toHaveTextContent("merchant-verification-key");
});

test.each([[403, "denied"], [503, "dependency"]] as const)(
  "classifies %s workspace failures as %s without leaking the credential",
  async (statusCode, state) => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      message: "Safe closed failure", requestId: `failure-${statusCode}`,
    }), { status: statusCode }))));
    const user = userEvent.setup();
    renderApp();
    await user.type(screen.getByLabelText("Risk operator credential"), "classified-memory-key");
    await user.click(screen.getByRole("button", { name: "Open workspace" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveAttribute("data-failure-state", state);
    expect(document.body).not.toHaveTextContent("classified-memory-key");
  },
);

test("loads generation-qualified analytics and keeps the credential in memory only", async () => {
  const freshness = {
    generationId: "a1000000-0000-0000-0000-000000000001",
    generationNumber: 1,
    sourceCutoff: "2026-07-28T08:00:00Z",
    lastAppliedEventTime: "2026-07-28T07:59:59Z",
    lastProcessingTime: new Date().toISOString(),
    state: "FRESH",
    eligibleEventCount: 1,
    appliedEventCount: 1,
    unavailableStreamCount: 0,
    trafficOriginPolicy: "ALL",
  };
  const payment = {
    paymentIntentId: "a2000000-0000-0000-0000-000000000002",
    intentAmountMinor: 1250, currency: "USD", captureMethod: "AUTOMATIC",
    trafficOrigin: "MERCHANT_API", attemptNumber: 1, attemptOutcome: "APPROVED",
    processorCode: "APPROVED", fraudAction: "REVIEW", riskLevel: "HIGH",
    riskScoreBasisPoints: 8700,
    reasonCodesJson: "[\"VELOCITY\"]", rulesetVersion: "rules-v1",
    featureCatalogueVersion: "features-v1",
    fraudDecisionId: "a4000000-0000-0000-0000-000000000004",
    featureSnapshotId: "a5000000-0000-0000-0000-000000000005",
    reviewStatus: "OPEN", reviewOutcome: null,
    capturedAmountMinor: 0, refundedAmountMinor: 0, disputedAmountMinor: 0,
    labelValue: null, fraudType: null, labelSource: null, labelAvailableAt: null,
    lifecycleStatus: "REQUIRES_REVIEW", coverageStatus: "COMPLETE", lastStreamVersion: 3,
    lastDomainEventId: "a3000000-0000-0000-0000-000000000003",
    lastEventRecordSha256: "a".repeat(64), lastEventOccurredAt: "2026-07-28T07:59:58Z",
    lastEventRecordedAt: "2026-07-28T07:59:59Z", lastProcessedAt: freshness.lastProcessingTime,
  };
  const summary = { from: "2026-07-27T08:00:00Z", to: "2026-07-28T08:00:00Z",
    freshness, nextCursor: "summary-cursor", currencies: [{ currency: "USD", paymentCount: 1, attemptCount: 1,
      authorizationCount: 0, declineCount: 0, reviewCount: 1, confirmedFraudCount: 0,
      chargebackCount: 0, enumerationSignalCount: 0, unlabeledCount: 1,
      unknownOutcomeCount: 0, intentAmountMinor: 1250, authorizedAmountMinor: 0,
      capturedAmountMinor: 0, refundedAmountMinor: 0, disputedAmountMinor: 0,
      sourceEventCount: 3 }] };
  const timeSeries = { from: summary.from, to: summary.to, interval: "HOUR", freshness,
    nextCursor: "timeseries-cursor",
    points: [{ ...summary.currencies[0], intervalStart: "2026-07-28T07:00:00Z",
      latestSourceOccurredAt: payment.lastEventOccurredAt,
      latestSourceRecordedAt: payment.lastEventRecordedAt,
      lastProcessedAt: payment.lastProcessedAt }] };
  const projectionStatus = { paymentRisk: freshness, merchantRisk: freshness,
    nextCursor: "generation-cursor",
    generations: [{ projectionName: "payment-risk-v1", generationId: freshness.generationId,
      generationNumber: 1, handlerVersion: "v1", buildSource: "LIVE",
      lifecycleStatus: "ACTIVE", createdAt: "2026-07-28T00:00:00Z",
      reconciledAt: "2026-07-28T00:00:00Z", activatedAt: "2026-07-28T00:00:00Z",
      retiredAt: null }] };
  const nextPayment = { ...payment,
    paymentIntentId: "a6000000-0000-0000-0000-000000000006",
    intentAmountMinor: 500, currency: "EUR", riskScoreBasisPoints: 4200,
    fraudDecisionId: "a7000000-0000-0000-0000-000000000007",
    featureSnapshotId: "a8000000-0000-0000-0000-000000000008" };
  const eurSummary = { ...summary.currencies[0], currency: "EUR", paymentCount: 2,
    capturedAmountMinor: 500 };
  const nextPoint = { ...eurSummary, intervalStart: "2026-07-28T08:00:00Z",
    latestSourceOccurredAt: payment.lastEventOccurredAt,
    latestSourceRecordedAt: payment.lastEventRecordedAt,
    lastProcessedAt: payment.lastProcessedAt };
  const nextGeneration = { ...projectionStatus.generations[0],
    projectionName: "merchant-risk-v1",
    generationId: "a9000000-0000-0000-0000-000000000009" };
  const fetchMock = enqueueConnect(vi.fn())
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: [payment],
      nextCursor: "payment-cursor",
      freshness }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(summary), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(timeSeries), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(projectionStatus), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: payment, freshness }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: [nextPayment], nextCursor: null,
      freshness }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ ...summary, currencies: [eurSummary],
      nextCursor: null }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ ...timeSeries, points: [nextPoint],
      nextCursor: null }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ ...projectionStatus,
      generations: [nextGeneration], nextCursor: null }), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "analytics-memory-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));
  await user.click(await screen.findByRole("button", { name: "Load analytics" }));

  expect(await screen.findByText("USD 12.50")).toBeVisible();
  expect(screen.getByText("87.00%", { exact: false })).toBeVisible();
  expect(screen.queryByText("8700", { exact: true })).not.toBeInTheDocument();
  expect(screen.getAllByText("FRESH").length).toBeGreaterThan(0);
  expect(screen.getByText("payment-risk-v1")).toBeVisible();
  await user.click(screen.getByRole("button", { name: payment.paymentIntentId }));
  expect(await screen.findByText("rules-v1")).toBeVisible();
  expect(screen.getByText(payment.fraudDecisionId)).toBeVisible();
  expect(screen.getByText(payment.featureSnapshotId)).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Load next analytics page" }));
  expect(await screen.findByRole("button", { name: nextPayment.paymentIntentId })).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Load more currencies" }));
  await user.click(screen.getByRole("button", { name: "Load more intervals" }));
  await user.click(screen.getByRole("button", { name: "Load more generations" }));
  expect(await screen.findByText("merchant-risk-v1")).toBeVisible();
  expect(screen.getAllByText("EUR")).toHaveLength(2);
  expect(fetchMock.mock.calls[7][0]).toBe("/risk/analytics/payments?limit=50");
  expect(fetchMock.mock.calls[8][0]).toContain(
    "/risk/analytics/merchant/summary?originPolicy=ALL&limit=100");
  expect(fetchMock.mock.calls[12][0]).toContain("cursor=payment-cursor");
  expect(fetchMock.mock.calls[13][0]).toContain("cursor=summary-cursor");
  expect(fetchMock.mock.calls[14][0]).toContain("cursor=timeseries-cursor");
  expect(fetchMock.mock.calls[15][0]).toContain("cursor=generation-cursor");
  expect(document.body).not.toHaveTextContent("analytics-memory-key");
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});

test("withholds numeric analytics when projection coverage is partial", async () => {
  const partial = { generationId: "b1000000-0000-0000-0000-000000000001",
    generationNumber: 1, sourceCutoff: "2026-07-28T08:00:00Z",
    lastAppliedEventTime: null, lastProcessingTime: null, state: "PARTIAL",
    eligibleEventCount: 9, appliedEventCount: 0, unavailableStreamCount: 1,
    trafficOriginPolicy: "ALL" };
  const hiddenCurrency = { currency: "USD", paymentCount: 0, attemptCount: 0,
    authorizationCount: 0, declineCount: 0, reviewCount: 0, confirmedFraudCount: 0,
    chargebackCount: 0, enumerationSignalCount: 0, unlabeledCount: 0,
    unknownOutcomeCount: 0, intentAmountMinor: 0, authorizedAmountMinor: 0,
    capturedAmountMinor: 0, refundedAmountMinor: 0, disputedAmountMinor: 0,
    sourceEventCount: 0 };
  const fetchMock = enqueueConnect(vi.fn())
    .mockResolvedValueOnce(new Response(JSON.stringify({ data: [], nextCursor: null,
      freshness: partial }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ from: "2026-07-27T08:00:00Z",
      to: "2026-07-28T08:00:00Z", currencies: [hiddenCurrency], nextCursor: null,
      freshness: partial }),
      { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ from: "2026-07-27T08:00:00Z",
      to: "2026-07-28T08:00:00Z", interval: "HOUR", points: [], nextCursor: null,
      freshness: partial }),
      { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ generations: [], nextCursor: null,
      paymentRisk: partial, merchantRisk: partial }), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();
  renderApp();
  await user.type(screen.getByLabelText("Risk operator credential"), "partial-memory-key");
  await user.click(screen.getByRole("button", { name: "Open workspace" }));
  await user.click(await screen.findByRole("button", { name: "Load analytics" }));

  expect((await screen.findAllByText("PARTIAL")).length).toBeGreaterThan(0);
  expect(screen.getAllByText(/incomplete totals are withheld/i).length).toBeGreaterThan(0);
  expect(screen.queryByText("USD 0.00")).not.toBeInTheDocument();
  expect(screen.queryByText("0 applied of 9 eligible events")).not.toBeInTheDocument();
  expect(document.body).not.toHaveTextContent("partial-memory-key");
});
