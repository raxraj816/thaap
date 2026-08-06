import { cleanup, render, screen } from "@testing-library/react";
import axe from "axe-core";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { Phase6Workspace } from "./Phase6Workspace";

const manager = { id: "30000000-0000-0000-0000-000000000003",
  operatorCode: "manager", role: "RISK_MANAGER" as const };

const freshness = { generationId: "40000000-0000-0000-0000-000000000004",
  generationNumber: 1, sourceCutoff: "2026-08-01T00:00:00Z",
  lastAppliedEventTime: "2026-08-01T00:00:00Z", lastProcessingTime: "2026-08-01T00:00:01Z",
  state: "FRESH", eligibleEventCount: 4, appliedEventCount: 4, unavailableStreamCount: 0,
  trafficOriginPolicy: "ALL" };

function decisionInsights(overrides: Record<string, unknown> = {}) {
  return {
    range: { from: "2026-07-25T00:00:00Z", to: "2026-08-01T00:00:00Z" },
    trafficOriginPolicy: "ALL", freshness,
    decisionDistribution: [{ code: "ALLOW", count: 3 }, { code: "BLOCK", count: 1 }],
    rates: {
      labelCoverage: { numerator: 2, denominator: 4, unknownCount: 2,
        valueBasisPoints: 5000, status: "COMPUTABLE" },
      confirmedFraudRate: { numerator: 0, denominator: 0, unknownCount: 0,
        valueBasisPoints: null, status: "NOT_COMPUTABLE_EMPTY_DENOMINATOR" },
    },
    moneyByCurrency: [
      { currency: "EUR", capturedAmountMinor: 7000, refundedAmountMinor: 0,
        disputedAmountMinor: 0 },
      { currency: "USD", capturedAmountMinor: 5000, refundedAmountMinor: 1200,
        disputedAmountMinor: 0 },
    ],
    chargebackPaymentCount: 0, warningCode: null, claimBoundary: "DESCRIPTIVE_NOT_CAUSAL",
    ...overrides };
}

function reviewInsights(overrides: Record<string, unknown> = {}) {
  return {
    range: { from: "2026-07-25T00:00:00Z", to: "2026-08-01T00:00:00Z" },
    sourceAsOf: "2026-08-01T12:00:00Z",
    backlog: { total: 5, unassigned: 2, assigned: 3, withinSla: 4, breached: 1 },
    assignments: 6, releases: 1, resolutions: 4, conflictedAttempts: null,
    conflictedAttemptsStatus: "CONFLICT_ATTEMPTS_NOT_AVAILABLE_WITHOUT_DURABLE_CONFLICT_FACTS",
    authority: "AUTHORITATIVE_REVIEW_SOURCE",
    timeToAssignment: { eligibleCount: 6, totalMilliseconds: 60000, minimumMilliseconds: 5000,
      maximumMilliseconds: 20000, missingCount: 0 },
    timeToResolution: { eligibleCount: 4, totalMilliseconds: 80000, minimumMilliseconds: 10000,
      maximumMilliseconds: 30000, missingCount: 0 },
    ...overrides };
}

function routed(decision: unknown, review: unknown) {
  return vi.fn().mockImplementation((url: string) => {
    if (url.includes("decision-outcomes")) {
      return decision instanceof Error
        ? Promise.reject(decision)
        : Promise.resolve(new Response(JSON.stringify(decision), { status: 200 }));
    }
    if (url.includes("review-operations")) {
      return review instanceof Error
        ? Promise.reject(review)
        : Promise.resolve(new Response(JSON.stringify(review), { status: 200 }));
    }
    return Promise.resolve(new Response("{}", { status: 200 }));
  });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test("a not-computable rate reads as not computable and never as zero percent", async () => {
  vi.stubGlobal("fetch", routed(decisionInsights(), reviewInsights()));
  const { container } = render(<Phase6Workspace credential="memory-only-risk-key"
    operator={manager} path="/workspace/insights" navigate={vi.fn()} />);

  expect(await screen.findByText("Not computable")).toBeInTheDocument();
  // The computable rate still shows its exact raw counts beside the percentage, so a reader can
  // always see how thin the denominator is.
  expect(screen.getByText("50.00%")).toBeInTheDocument();
  expect(screen.getByText("2 / 4 · 2 unknown")).toBeInTheDocument();
  expect((await axe.run(container)).violations).toEqual([]);
});

test("money is rendered one currency at a time and never as a blended total", async () => {
  vi.stubGlobal("fetch", routed(decisionInsights(), reviewInsights()));
  render(<Phase6Workspace credential="k" operator={manager} path="/workspace/insights"
    navigate={vi.fn()} />);

  expect(await screen.findByText("USD")).toBeInTheDocument();
  expect(screen.getByText("EUR")).toBeInTheDocument();

  // 5000 USD minor + 7000 EUR minor must never appear as one 12000 figure in any format.
  const body = document.body.textContent ?? "";
  for (const blended of ["120.00", "12000", "$120.00"]) {
    expect(body).not.toContain(blended);
  }
});

test("the two authorities keep separate timestamps and are never merged into one as-of",
  async () => {
    vi.stubGlobal("fetch", routed(decisionInsights(), reviewInsights()));
    render(<Phase6Workspace credential="k" operator={manager} path="/workspace/insights"
      navigate={vi.fn()} />);

    // The source side publishes exactly one as-of; the projection side publishes a range.
    expect(await screen.findByText(/Source as of/)).toBeInTheDocument();
    expect(screen.getAllByText(/Source as of/)).toHaveLength(1);
    expect(screen.getByText(/—/))
      .toHaveTextContent(/^\S.*—.*\S$/);
    expect(screen.getByText("Descriptive not causal")).toBeInTheDocument();
  });

test("a failed projection slice leaves the source-backed review section fully readable",
  async () => {
    const failure = Object.assign(new Error("projection unavailable"),
      { status: 503, requestId: "r-9" });
    vi.stubGlobal("fetch", routed(failure, reviewInsights()));
    render(<Phase6Workspace credential="k" operator={manager} path="/workspace/insights"
      navigate={vi.fn()} />);

    expect(await screen.findByText("Decision insights unavailable")).toBeInTheDocument();
    // The independent authority survives: an outage in one must not erase the other.
    expect(screen.getByText(/Source as of/)).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
  });

test("a failed source slice leaves the projection-backed decision section fully readable",
  async () => {
    const failure = Object.assign(new Error("source timeout"), { status: 503 });
    vi.stubGlobal("fetch", routed(decisionInsights(), failure));
    render(<Phase6Workspace credential="k" operator={manager} path="/workspace/insights"
      navigate={vi.fn()} />);

    expect(await screen.findByText("Review insights unavailable")).toBeInTheDocument();
    expect(screen.getByText("Not computable")).toBeInTheDocument();
    expect(screen.getByText("USD")).toBeInTheDocument();
  });

test("partial projection coverage is announced rather than silently rendered as complete",
  async () => {
    vi.stubGlobal("fetch", routed(
      decisionInsights({ warningCode: "derived_rates_and_money_withheld_for_partial_coverage",
        moneyByCurrency: [] }),
      reviewInsights()));
    render(<Phase6Workspace credential="k" operator={manager} path="/workspace/insights"
      navigate={vi.fn()} />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("withheld");
    expect(screen.queryByText("USD")).toBeNull();
  });

test("unavailable conflict history is named as unavailable rather than shown as zero", async () => {
  vi.stubGlobal("fetch", routed(decisionInsights(), reviewInsights()));
  render(<Phase6Workspace credential="k" operator={manager} path="/workspace/insights"
    navigate={vi.fn()} />);

  // Rendering 0 here would assert "no conflicts happened", which the source cannot support.
  expect(await screen.findByText(/Conflict attempts:/)).toHaveTextContent(
    "Conflict attempts not available without durable conflict facts");
});

test("the credential never reaches the insights DOM", async () => {
  vi.stubGlobal("fetch", routed(decisionInsights(), reviewInsights()));
  render(<Phase6Workspace credential="memory-only-risk-key" operator={manager}
    path="/workspace/insights" navigate={vi.fn()} />);

  await screen.findByText("Not computable");
  expect(document.body).not.toHaveTextContent("memory-only-risk-key");
});

test("a decision segment drills through to a filtered search using a tenant-free typed filter",
  async () => {
    const withDrill = decisionInsights({
      decisionDistribution: [
        { code: "ALLOW", count: 3, drillThrough: null },
        { code: "BLOCK", count: 1, drillThrough: { fraudActions: ["BLOCK"],
          createdFrom: "2026-07-25T00:00:00Z", createdTo: "2026-08-01T00:00:00Z",
          trafficOriginPolicy: "ALL" } },
      ],
    });
    const onDrillThrough = vi.fn();
    vi.stubGlobal("fetch", routed(withDrill, reviewInsights()));
    render(<Phase6Workspace credential="k" operator={manager} path="/workspace/insights"
      navigate={onDrillThrough} />);

    const drillControl = await screen.findByRole("button", { name: /Block/ });
    await userEvent.setup().click(drillControl);

    // Navigating to the search route is the whole visible effect; the filter travels in memory.
    expect(onDrillThrough).toHaveBeenCalledWith("/workspace/payments");
  });

test("a segment the search grammar cannot express offers no drill-through control", async () => {
  const withUnknown = decisionInsights({
    decisionDistribution: [{ code: "UNKNOWN", count: 2, drillThrough: null }],
  });
  vi.stubGlobal("fetch", routed(withUnknown, reviewInsights()));
  render(<Phase6Workspace credential="k" operator={manager} path="/workspace/insights"
    navigate={vi.fn()} />);

  // UNKNOWN is a projection placeholder, not a filterable action. Offering a link that cannot
  // reproduce its own segment would send an analyst to a result set that disagrees with the card.
  expect(await screen.findByText("Unknown")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /Unknown/ })).toBeNull();
});

test("no drill-through descriptor carries a tenant, operator, or generation identifier",
  async () => {
    const withDrill = decisionInsights({
      decisionDistribution: [{ code: "BLOCK", count: 1, drillThrough: { fraudActions: ["BLOCK"],
        createdFrom: "2026-07-25T00:00:00Z", createdTo: "2026-08-01T00:00:00Z",
        trafficOriginPolicy: "ALL" } }],
    });
    vi.stubGlobal("fetch", routed(withDrill, reviewInsights()));
    render(<Phase6Workspace credential="memory-only-risk-key" operator={manager}
      path="/workspace/insights" navigate={vi.fn()} />);

    await screen.findByRole("button", { name: /Block/ });
    const body = document.body.innerHTML;
    // The server re-derives the tenant from the caller's own credential on replay, so the
    // descriptor must not carry one — a copied filter cannot reach another tenant's payments.
    expect(body).not.toContain("merchantId");
    expect(body).not.toContain("generationId");
    expect(body).not.toContain("memory-only-risk-key");
  });
