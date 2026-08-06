import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { Phase6Workspace } from "./Phase6Workspace";

const paymentId = "10000000-0000-0000-0000-000000000001";
const relatedId = "20000000-0000-0000-0000-000000000002";
const manager = { id: "30000000-0000-0000-0000-000000000003",
  operatorCode: "manager", role: "RISK_MANAGER" as const };
const freshness = { generationId: "40000000-0000-0000-0000-000000000004",
  generationNumber: 1, sourceCutoff: "2026-08-01T00:00:00Z",
  lastAppliedEventTime: "2026-08-01T00:00:00Z", lastProcessingTime: "2026-08-01T00:00:01Z",
  state: "FRESH", eligibleEventCount: 1, appliedEventCount: 1, unavailableStreamCount: 0,
  trafficOriginPolicy: "ALL" };

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test("opens only server-returned payment identifiers from a bounded projection search", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
    data: [{ paymentIntentId: paymentId, amountMinor: 1250, currency: "USD",
      lifecycleStatus: "REQUIRES_REVIEW", fraudAction: "REVIEW", riskLevel: "HIGH",
      riskScoreBasisPoints: 7500, reviewStatus: "OPEN", labelValue: null,
      trafficOrigin: "MERCHANT_API", coverageStatus: "COMPLETE",
      lastEventRecordedAt: "2026-08-01T00:00:01Z" }], nextCursor: null, freshness,
  }), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const navigate = vi.fn();
  const { container } = render(<Phase6Workspace credential="memory-only-risk-key" operator={manager}
    path="/workspace/payments" navigate={navigate} />);

  await userEvent.setup().click(await screen.findByRole("button", { name: paymentId }));
  expect(navigate).toHaveBeenCalledWith(`/workspace/payments/${paymentId}`);
  expect(document.body).not.toHaveTextContent("memory-only-risk-key");
  expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/^\/risk\/workspace\/payments\/search/),
    expect.objectContaining({ credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
      headers: expect.objectContaining({ "X-Risk-Operator-Key": "memory-only-risk-key",
        "Thaap-Version": "2026-08-01" }) }));
  expect((await axe.run(container)).violations).toEqual([]);
});

test("keeps source detail and related pivots usable when timeline transport is unavailable", async () => {
  const detail = {
    identity: { paymentIntentId: paymentId, latestPaymentAttemptId: null,
      latestFraudDecisionId: null, latestReviewCaseId: null, trafficOrigin: "MERCHANT_API" },
    sourceSnapshot: { sourceAsOf: "2026-08-01T00:00:02Z", maximumPaymentStreamVersion: 4,
      consistency: "REPEATABLE_READ", authority: "SOURCE_DATABASE" }, analyticsContext: null,
    paymentLifecycle: { status: "REQUIRES_REVIEW", amountMinor: 1250, currency: "USD",
      captureMethod: "AUTOMATIC", createdAt: "2026-08-01T00:00:00Z",
      updatedAt: "2026-08-01T00:00:01Z", attempts: [] }, decisionExplanation: null,
    reviewAndLabels: { reviews: [], labels: [] }, moneyAndDisputes: { currency: "USD",
      capturedAmountMinor: 0, refundedAmountMinor: 0, disputedAmountMinor: 0,
      ledgerDebitMinor: 0, ledgerCreditMinor: 0, ledgerBalanced: true, disputes: [] },
    webhookSummary: { deliveryCount: 0, statuses: [] },
    availableActions: [{ action: "ASSIGN_TO_SELF", eligible: true, reasonCode: null }],
    sectionStatuses: {
      paymentLifecycle: { state: "AVAILABLE", authority: "SOURCE_DATABASE", reasonCode: null },
      decisionExplanation: { state: "NOT_APPLICABLE", authority: "DECISION_TIME_SOURCE", reasonCode: null },
      reviewAndLabels: { state: "EMPTY", authority: "REVIEW_SOURCE", reasonCode: null },
      moneyAndDisputes: { state: "EMPTY", authority: "LEDGER_AND_DISPUTE_SOURCE", reasonCode: null },
    },
  };
  const related = { from: "2026-07-25T00:00:00Z", to: "2026-08-02T00:00:00Z",
    asOf: "2026-08-01T00:00:02Z", data: [{ paymentIntentId: relatedId,
      reasons: ["SAME_CARD_FINGERPRINT"], matchedReasonCount: 1,
      paymentAndDecision: null, sectionState: "PARTIAL" }], nextCursor: null,
    coverage: { state: "AVAILABLE", availableReasons: ["SAME_CARD_FINGERPRINT"],
      unavailableReasons: [], reasonCode: null } };
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(detail), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ message: "Timeline dependency unavailable",
      requestId: "timeline-503" }), { status: 503 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(related), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const navigate = vi.fn();
  render(<Phase6Workspace credential="detail-key" operator={manager}
    path={`/workspace/payments/${paymentId}`} navigate={navigate} />);

  expect(await screen.findByText(/Requires review/)).toBeVisible();
  expect(screen.getByText(/\$12\.50/)).toBeVisible();
  expect(screen.getByText(/Timeline unavailable/)).toBeVisible();
  expect(screen.getByText(/timeline-503/)).toBeVisible();
  await userEvent.setup().click(screen.getByRole("button", { name: relatedId }));
  expect(navigate).toHaveBeenCalledWith(`/workspace/payments/${relatedId}`);
  expect(screen.getByText(/All relation types covered/)).toBeVisible();
});

test("renders bulk review operations read-only for a live viewer role", () => {
  render(<Phase6Workspace credential="viewer-key" operator={{ ...manager, role: "VIEWER" }}
    path="/workspace/bulk" navigate={vi.fn()} />);
  expect(screen.getByRole("alert")).toHaveTextContent("Viewer access is read-only");
  expect(screen.queryByRole("button", { name: "Start durable bulk action" })).not.toBeInTheDocument();
});
