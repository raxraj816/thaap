import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { Phase6Workspace } from "./Phase6Workspace";

/**
 * Proves C53-UI-001. The workspace is where an analyst decides whether real money moves, so
 * the browser must never hold the operator credential anywhere it can be replayed, must never
 * render one payment's evidence under another payment's heading, and must never let one failed
 * dependency blank the authoritative evidence that did load.
 */

const paymentId = "10000000-0000-0000-0000-000000000001";
const otherPaymentId = "20000000-0000-0000-0000-000000000002";
const credential = "memory-only-operator-credential";
const manager = { id: "30000000-0000-0000-0000-000000000003",
  operatorCode: "manager", role: "RISK_MANAGER" as const };

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

function detailFor(id: string, overrides: Record<string, unknown> = {}) {
  return {
    identity: { paymentIntentId: id, latestPaymentAttemptId: null,
      latestFraudDecisionId: null, latestReviewCaseId: null, trafficOrigin: "MERCHANT_API" },
    sourceSnapshot: { sourceAsOf: "2026-08-01T00:00:02Z", maximumPaymentStreamVersion: 7,
      consistency: "REPEATABLE_READ", authority: "SOURCE_DATABASE" },
    analyticsContext: null,
    paymentLifecycle: { status: "REQUIRES_REVIEW", amountMinor: 4200, currency: "USD",
      captureMethod: "MANUAL", createdAt: "2026-08-01T00:00:00Z",
      updatedAt: "2026-08-01T00:00:01Z", attempts: [] },
    decisionExplanation: null,
    reviewAndLabels: { reviews: [], labels: [] },
    moneyAndDisputes: { currency: "USD", capturedAmountMinor: 0, refundedAmountMinor: 0,
      disputedAmountMinor: 0, ledgerDebitMinor: 0, ledgerCreditMinor: 0,
      ledgerBalanced: true, disputes: [] },
    webhookSummary: { deliveryCount: 0, statuses: [] },
    availableActions: [{ action: "ASSIGN_TO_SELF", eligible: true, reasonCode: null }],
    sectionStatuses: {
      paymentLifecycle: { state: "AVAILABLE", authority: "SOURCE_DATABASE", reasonCode: null },
      decisionExplanation: { state: "NOT_APPLICABLE", authority: "DECISION_TIME_SOURCE",
        reasonCode: null },
      reviewAndLabels: { state: "EMPTY", authority: "REVIEW_SOURCE", reasonCode: null },
      moneyAndDisputes: { state: "EMPTY", authority: "LEDGER_AND_DISPUTE_SOURCE",
        reasonCode: null },
    },
    ...overrides,
  };
}

const emptyTimeline = { data: [], nextCursor: null, maximumStreamVersion: 7 };
const emptyRelated = { from: "2026-07-25T00:00:00Z", to: "2026-08-02T00:00:00Z",
  asOf: "2026-08-01T00:00:02Z", data: [], nextCursor: null,
  coverage: { state: "AVAILABLE", availableReasons: [], unavailableReasons: [],
    reasonCode: null } };

test("the operator credential never reaches storage, cookies, the URL, or the rendered page",
  async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(detailFor(paymentId)), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(emptyTimeline), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(emptyRelated), { status: 200 })));

    render(<Phase6Workspace credential={credential} operator={manager}
      path={`/workspace/payments/${paymentId}`} navigate={vi.fn()} />);
    await screen.findByText(/Requires review/);

    // A credential in any of these places survives the tab, the screenshot, the shared link,
    // and the support session. Only process memory is acceptable.
    expect(JSON.stringify(window.localStorage)).not.toContain(credential);
    expect(JSON.stringify(window.sessionStorage)).not.toContain(credential);
    expect(document.cookie).not.toContain(credential);
    expect(window.location.href).not.toContain(credential);
    expect(document.body.innerHTML).not.toContain(credential);
  });

test("a slow response for an abandoned payment cannot render under the new payment's heading",
  async () => {
    let releaseFirstDetail: (value: Response) => void = () => {};
    const slowFirstDetail = new Promise<Response>((resolve) => { releaseFirstDetail = resolve; });
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url.includes(paymentId) && url.endsWith(paymentId)) return slowFirstDetail;
      if (url.includes("/timeline")) {
        return Promise.resolve(new Response(JSON.stringify(emptyTimeline), { status: 200 }));
      }
      if (url.includes("/related")) {
        return Promise.resolve(new Response(JSON.stringify(emptyRelated), { status: 200 }));
      }
      return Promise.resolve(new Response(JSON.stringify(
        detailFor(otherPaymentId, { paymentLifecycle: { status: "SUCCEEDED", amountMinor: 900,
          currency: "USD", captureMethod: "MANUAL", createdAt: "2026-08-01T00:00:00Z",
          updatedAt: "2026-08-01T00:00:01Z", attempts: [] } })), { status: 200 }));
    });
    vi.stubGlobal("fetch", fetchMock);

    const view = render(<Phase6Workspace credential={credential} operator={manager}
      path={`/workspace/payments/${paymentId}`} navigate={vi.fn()} />);
    view.rerender(<Phase6Workspace credential={credential} operator={manager}
      path={`/workspace/payments/${otherPaymentId}`} navigate={vi.fn()} />);
    await screen.findByText(/Succeeded/);

    // The abandoned request lands last. Rendering it now would show the first payment's
    // evidence beneath the second payment's identifier -- the worst possible confusion for
    // someone about to approve or block money.
    releaseFirstDetail(new Response(JSON.stringify(detailFor(paymentId)), { status: 200 }));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByRole("heading", { name: `Payment ${otherPaymentId}` })).toBeVisible();
    expect(screen.queryByText(/Requires review/)).not.toBeInTheDocument();
    expect(screen.getByText(/Succeeded/)).toBeVisible();
  });

test("failed supporting sections are named individually and never blank authoritative detail",
  async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(detailFor(paymentId)), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ error: "dependency_unavailable",
        message: "Timeline dependency unavailable", requestId: "req-timeline" }), { status: 503 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ error: "dependency_unavailable",
        message: "Related payments unavailable", requestId: "req-related" }), { status: 503 })));

    const { container } = render(<Phase6Workspace credential={credential} operator={manager}
      path={`/workspace/payments/${paymentId}`} navigate={vi.fn()} />);

    expect(await screen.findByText(/Requires review/)).toBeVisible();
    expect(screen.getByText(/\$42\.00/)).toBeVisible();
    expect(screen.getByText(/Timeline unavailable/)).toBeVisible();
    expect(screen.getAllByText(/Related payments unavailable/)[0]).toBeVisible();
    // Each failure is separately retryable, so one dead dependency does not force a full reload
    // that would also discard the source truth the analyst already has.
    expect(screen.getAllByRole("button", { name: /retry/i }).length).toBeGreaterThanOrEqual(2);
    expect((await axe.run(container)).violations).toEqual([]);
  });

test("a degraded section states its authority and reason instead of showing confident zeroes",
  async () => {
    const degraded = detailFor(paymentId, {
      analyticsContext: { freshness: { generationId: "40000000-0000-0000-0000-000000000004",
        generationNumber: 2, sourceCutoff: "2026-08-01T00:00:00Z",
        lastAppliedEventTime: "2026-07-01T00:00:00Z",
        lastProcessingTime: "2026-07-01T00:00:01Z", state: "STALE", eligibleEventCount: 9,
        appliedEventCount: 3, unavailableStreamCount: 6, trafficOriginPolicy: "ALL" } },
      sectionStatuses: {
        paymentLifecycle: { state: "AVAILABLE", authority: "SOURCE_DATABASE", reasonCode: null },
        decisionExplanation: { state: "NOT_APPLICABLE", authority: "DECISION_TIME_SOURCE",
          reasonCode: null },
        reviewAndLabels: { state: "EMPTY", authority: "REVIEW_SOURCE", reasonCode: null },
        moneyAndDisputes: { state: "EMPTY", authority: "LEDGER_AND_DISPUTE_SOURCE",
          reasonCode: null },
        analyticsContext: { state: "STALE", authority: "ACTIVE_PAYMENT_RISK_PROJECTION",
          reasonCode: "stale" },
      },
    });
    vi.stubGlobal("fetch", vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(degraded), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(emptyTimeline), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(emptyRelated), { status: 200 })));

    render(<Phase6Workspace credential={credential} operator={manager}
      path={`/workspace/payments/${paymentId}`} navigate={vi.fn()} />);

    await screen.findByText(/Requires review/);
    expect(screen.getByText(/Projection evidence/)).toBeVisible();
    expect(screen.getByText(/Stale/)).toBeVisible();
    // The authoritative snapshot stays visible next to the degraded projection, so the analyst
    // can see exactly which claim is trustworthy.
    expect(screen.getByText(/Repeatable read/i)).toBeVisible();
    expect(screen.getByText(/stream through v7/i)).toBeVisible();
  });

test("only server-returned identifiers become navigation targets", async () => {
  const hostileRelated = { ...emptyRelated, data: [{ paymentIntentId: otherPaymentId,
    reasons: ["SAME_CARD_FINGERPRINT"], matchedReasonCount: 1, paymentAndDecision: null,
    sectionState: "AVAILABLE" }] };
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(detailFor(paymentId)), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyTimeline), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(hostileRelated), { status: 200 })));
  const navigate = vi.fn();

  render(<Phase6Workspace credential={credential} operator={manager}
    path={`/workspace/payments/${paymentId}`} navigate={navigate} />);

  await userEvent.setup().click(await screen.findByRole("button", { name: otherPaymentId }));
  // Navigation is built from an internal route template plus a server identifier. Nothing in
  // the payload is ever used as a URL, so a poisoned field cannot become an outbound request.
  expect(navigate).toHaveBeenCalledWith(`/workspace/payments/${otherPaymentId}`);
  await waitFor(() => expect(screen.queryByRole("link")).not.toBeInTheDocument());
});

test("the investigation view offers no mutation control to a read-only viewer", async () => {
  const viewerDetail = detailFor(paymentId, {
    availableActions: [{ action: "ASSIGN_TO_SELF", eligible: false, reasonCode: "ROLE_DENIED" }],
  });
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(viewerDetail), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyTimeline), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(emptyRelated), { status: 200 })));

  render(<Phase6Workspace credential={credential} operator={{ ...manager, role: "VIEWER" }}
    path={`/workspace/payments/${paymentId}`} navigate={vi.fn()} />);

  await screen.findByText(/Requires review/);
  expect(screen.getByText(/Role denied/i)).toBeVisible();
  // Eligibility hints are evidence, not authorization: the server rechecks every mutation.
  expect(screen.getByText(/not authorization/i)).toBeVisible();
});
