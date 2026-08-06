import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { Phase6Workspace } from "./Phase6Workspace";

/**
 * Proves C54-UI-001. The timeline is the analyst's account of what actually happened to a
 * payment, and the related list is the pivot they use to decide whether one bad payment is
 * really a ring. Both are read seconds before money is approved or blocked, so the browser must
 * preserve canonical order, never invent a relationship, never leak the identity that produced
 * one, and never let a failed pivot blank the timeline that did load.
 */

const paymentId = "10000000-0000-0000-0000-000000000001";
const siblingId = "20000000-0000-0000-0000-000000000002";
const credential = "memory-only-operator-credential";
const manager = { id: "30000000-0000-0000-0000-000000000003",
  operatorCode: "manager", role: "RISK_MANAGER" as const };

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

function detail() {
  return {
    identity: { paymentIntentId: paymentId, latestPaymentAttemptId: null,
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
  };
}

function entry(streamVersion: number, titleCode: string, eventId: string) {
  return { streamVersion, eventId, eventType: titleCode.replace("timeline.", ""), eventVersion: 1,
    occurredAt: "2026-08-01T00:00:00Z", recordedAt: "2026-08-01T00:00:01Z",
    actorType: "MERCHANT", trafficOrigin: "MERCHANT_API", category: "PAYMENT_LIFECYCLE",
    titleCode, safeFacts: {}, sourceLinks: [] };
}

function timelinePage(data: unknown[], nextCursor: string | null) {
  return { data, nextCursor, maximumStreamVersion: 7, ordering: "STREAM_VERSION_ASCENDING",
    state: { state: "AVAILABLE", authority: "SOURCE_DATABASE", reasonCode: null } };
}

const firstTimelinePage = timelinePage(
  [entry(1, "timeline.payment_intent.created", "aaaaaaaa-0000-0000-0000-000000000001"),
    entry(2, "timeline.payment_attempt.completed", "aaaaaaaa-0000-0000-0000-000000000002")],
  "cursor-page-two");
const secondTimelinePage = timelinePage(
  [entry(3, "timeline.fraud_decision.created", "aaaaaaaa-0000-0000-0000-000000000003")], null);

const relatedPage = { seedPaymentIntentId: paymentId, from: "2026-07-25T00:00:00Z",
  to: "2026-08-02T00:00:00Z", asOf: "2026-08-01T00:00:02Z", nextCursor: null,
  scope: "SAME_MERCHANT_SERVER_DERIVED",
  coverage: { state: "PARTIAL", availableReasons: ["SAME_EMAIL", "SAME_CARD_FINGERPRINT"],
    unavailableReasons: ["SAME_DEVICE"], reasonCode: "seed_identity_types_unavailable" },
  data: [{ paymentIntentId: siblingId, reasons: ["SAME_EMAIL", "SAME_CARD_FINGERPRINT"],
    matchedReasonCount: 2, firstObservedAt: "2026-07-30T00:00:00Z",
    lastObservedAt: "2026-07-31T00:00:00Z", trafficOrigins: ["MERCHANT_API"],
    paymentAndDecision: { status: "SUCCEEDED", amountMinor: 1500, currency: "USD",
      decisionOutcome: "ALLOW", coverageStatus: "COMPLETE" },
    sectionState: "AVAILABLE" }] };

function stubFetch(handlers: {
  timeline?: (url: string) => Response; related?: () => Response;
}) {
  const fetchMock = vi.fn().mockImplementation((url: string) => {
    if (url.includes("/timeline")) {
      return Promise.resolve(handlers.timeline
        ? handlers.timeline(url)
        : new Response(JSON.stringify(firstTimelinePage), { status: 200 }));
    }
    if (url.includes("/related")) {
      return Promise.resolve(handlers.related
        ? handlers.related()
        : new Response(JSON.stringify(relatedPage), { status: 200 }));
    }
    return Promise.resolve(new Response(JSON.stringify(detail()), { status: 200 }));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderWorkspace(navigate = vi.fn()) {
  return { navigate, ...render(<Phase6Workspace credential={credential} operator={manager}
    path={`/workspace/payments/${paymentId}`} navigate={navigate} />) };
}

test("the timeline renders in the server's canonical order and never renumbers it", async () => {
  stubFetch({});
  renderWorkspace();

  const versions = await waitFor(() => {
    const rendered = Array.from(document.querySelectorAll(".stream-version"))
      .map((node) => node.textContent);
    expect(rendered.length).toBeGreaterThan(0);
    return rendered;
  });
  // Stream version is the only defensible ordering of a payment's history. If the browser sorts
  // by a rendered timestamp instead, two events recorded in the same millisecond can swap and an
  // analyst reads the decision as happening before the attempt that caused it.
  expect(versions).toEqual(["v1", "v2"]);
});

test("loading later stream versions appends without dropping or repeating an event", async () => {
  stubFetch({ timeline: (url: string) => new Response(JSON.stringify(
    url.includes("cursor=") ? secondTimelinePage : firstTimelinePage), { status: 200 }) });
  renderWorkspace();

  await screen.findByText(/payment attempt\.completed/i);
  await userEvent.click(screen.getByRole("button", { name: /Load later stream versions/i }));

  await waitFor(() => {
    const versions = Array.from(document.querySelectorAll(".stream-version"))
      .map((node) => node.textContent);
    // A pager that replaces instead of appending silently hides the earlier half of the payment's
    // history, which is exactly the half that explains why the decision was made.
    expect(versions).toEqual(["v1", "v2", "v3"]);
  });
});

test("a partial relation coverage is stated plainly rather than shown as a complete picture",
  async () => {
    stubFetch({});
    renderWorkspace();

    await screen.findByText(/Unavailable relation types/i);
    // "No related payments" and "we could not check for related payments" lead to opposite
    // decisions. Presenting a partial pivot as complete is how a ring gets approved.
    expect(screen.getByText(/Same device/i)).toBeVisible();
    expect(screen.getAllByText(/Partial/i)[0]).toBeVisible();
  });

test("a related payment is shown with its reasons and pivots without leaving the workspace",
  async () => {
    const navigate = vi.fn();
    stubFetch({});
    renderWorkspace(navigate);

    const sibling = await screen.findByRole("button", { name: siblingId });
    const card = sibling.closest("article") as HTMLElement;
    expect(within(card).getByText(/Same email/i)).toBeVisible();

    await userEvent.click(sibling);
    // The pivot must be an in-app navigation to the sibling's own workspace, not a raw link the
    // analyst has to reconcile by hand.
    expect(navigate).toHaveBeenCalledWith(`/workspace/payments/${siblingId}`);
  });

test("no identity digest or raw contact value is ever rendered by the pivot", async () => {
  stubFetch({ related: () => new Response(JSON.stringify({ ...relatedPage,
    data: [{ ...relatedPage.data[0],
      // A server regression that started echoing the correlation digest must be visible here,
      // because a digest on screen is a digest in a screenshot, a ticket, and an export.
      relationIdentityHmac: "a".repeat(64), emailHash: "b".repeat(64) }] }),
  { status: 200 }) });
  renderWorkspace();

  await screen.findByRole("button", { name: siblingId });
  expect(document.body.innerHTML).not.toContain("a".repeat(64));
  expect(document.body.innerHTML).not.toContain("b".repeat(64));
  expect(document.body.innerHTML).not.toContain(credential);
});

test("a failed pivot names itself and never blanks the timeline that did load", async () => {
  stubFetch({ related: () => new Response(JSON.stringify({ error: "dependency_unavailable",
    message: "Related payments unavailable", requestId: "req-related" }), { status: 503 }) });
  renderWorkspace();

  expect(await screen.findByText(/payment intent\.created/i)).toBeVisible();
  // One degraded dependency must degrade one section. Blanking the page would push the analyst
  // to decide with no evidence at all.
  expect(screen.getAllByText(/Related payments unavailable/i)[0]).toBeVisible();
  expect(screen.getByRole("button", { name: /Load later stream versions/i })).toBeVisible();
});

test("the timeline and pivot sections are accessible to a keyboard and screen reader", async () => {
  stubFetch({});
  const { container } = renderWorkspace();
  await screen.findByRole("button", { name: siblingId });

  const results = await axe.run(container, {
    rules: { region: { enabled: false }, "color-contrast": { enabled: false } },
  });
  expect(results.violations.map((violation) => violation.id)).toEqual([]);
});
