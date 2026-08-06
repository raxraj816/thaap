import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { Phase6Workspace } from "./Phase6Workspace";

const paymentId = "10000000-0000-0000-0000-000000000001";
const viewId = "50000000-0000-0000-0000-000000000005";
const reviewOne = "60000000-0000-0000-0000-000000000006";
const reviewTwo = "70000000-0000-0000-0000-000000000007";
const bulkId = "80000000-0000-0000-0000-000000000008";

const manager = { id: "30000000-0000-0000-0000-000000000003",
  operatorCode: "manager", role: "RISK_MANAGER" as const };
const analyst = { id: "30000000-0000-0000-0000-00000000000a",
  operatorCode: "analyst", role: "ANALYST" as const };
const viewer = { id: "30000000-0000-0000-0000-00000000000b",
  operatorCode: "viewer", role: "VIEWER" as const };

const freshness = { generationId: "40000000-0000-0000-0000-000000000004",
  generationNumber: 1, sourceCutoff: "2026-08-01T00:00:00Z",
  lastAppliedEventTime: "2026-08-01T00:00:00Z", lastProcessingTime: "2026-08-01T00:00:01Z",
  state: "FRESH", eligibleEventCount: 1, appliedEventCount: 1, unavailableStreamCount: 0,
  trafficOriginPolicy: "ALL" };

function savedView(overrides: Record<string, unknown> = {}) {
  return { id: viewId, ownerOperatorId: manager.id, name: "High risk reviews",
    visibility: "PRIVATE", filterSchemaVersion: "payment_investigation_filter_v1",
    filter: { fraudActions: ["REVIEW"] }, sort: "CREATED_AT_DESC",
    visibleColumns: ["CREATED_AT"], definitionDigest: "a".repeat(64),
    lifecycleStatus: "ACTIVE", lifecycleVersion: 1, createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z", disabledAt: null, ...overrides };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test("a saved view stores only its typed definition and never its results", async () => {
  const fetchMock = vi.fn().mockResolvedValue(json([savedView()]));
  vi.stubGlobal("fetch", fetchMock);
  const { container } = render(<Phase6Workspace credential="memory-only-risk-key"
    operator={manager} path="/workspace/payment-views" navigate={vi.fn()} />);

  expect(await screen.findByText("High risk reviews")).toBeInTheDocument();
  // The digest is the definition's identity; the credential never reaches the DOM.
  expect(document.body).toHaveTextContent("aaaaaaaaaaaa");
  expect(document.body).not.toHaveTextContent("memory-only-risk-key");
  expect(fetchMock).toHaveBeenCalledWith("/risk/workspace/payment-views",
    expect.objectContaining({ credentials: "omit", redirect: "error",
      referrerPolicy: "no-referrer",
      headers: expect.objectContaining({ "X-Risk-Operator-Key": "memory-only-risk-key" }) }));
  expect((await axe.run(container)).violations).toEqual([]);
});

test("creating a view sends an idempotency key and a bounded typed filter, never free text",
  async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(savedView()))
      .mockResolvedValueOnce(json([savedView()]));
    vi.stubGlobal("fetch", fetchMock);
    render(<Phase6Workspace credential="memory-only-risk-key" operator={manager}
      path="/workspace/payment-views" navigate={vi.fn()} />);

    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("View name"), "High risk reviews");
    await user.selectOptions(screen.getByLabelText("Decision action filter"), "REVIEW");
    await user.click(screen.getByRole("button", { name: "Save this bounded view" }));

    await screen.findByText("High risk reviews");
    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe("/risk/workspace/payment-views");
    expect(init.method).toBe("POST");
    expect(init.headers["Idempotency-Key"]).toMatch(/[0-9a-f-]{36}/);
    expect(JSON.parse(init.body)).toEqual({ name: "High risk reviews", visibility: "PRIVATE",
      filter: { fraudActions: ["REVIEW"] }, sort: "CREATED_AT_DESC" });
  });

test("only a manager is offered merchant-shared visibility", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([])));
  const { rerender } = render(<Phase6Workspace credential="k" operator={analyst}
    path="/workspace/payment-views" navigate={vi.fn()} />);
  const analystOptions = within(await screen.findByLabelText("Visibility"))
    .getAllByRole("option").map((option) => option.textContent);
  expect(analystOptions).toEqual(["PRIVATE"]);

  rerender(<Phase6Workspace credential="k" operator={manager}
    path="/workspace/payment-views" navigate={vi.fn()} />);
  const managerOptions = within(await screen.findByLabelText("Visibility"))
    .getAllByRole("option").map((option) => option.textContent);
  expect(managerOptions).toEqual(["PRIVATE", "MERCHANT_SHARED"]);
});

test("a viewer is told saved views are read-only and is offered no mutation control", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([savedView()])));
  render(<Phase6Workspace credential="k" operator={viewer}
    path="/workspace/payment-views" navigate={vi.fn()} />);

  expect(await screen.findByRole("alert")).toHaveTextContent("read-only");
  expect(screen.queryByRole("button", { name: "Save this bounded view" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Disable" })).toBeNull();
  // A viewer may still execute a view it can see: reading is not a mutation.
  expect(await screen.findByRole("button", { name: "Execute" })).toBeInTheDocument();
});

test("disabling a view sends the exact version the operator saw, so a stale click conflicts",
  async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([savedView({ lifecycleVersion: 4 })]))
      .mockResolvedValueOnce(json({ requestId: "r-1", status: 409, error: "saved_view_stale",
        message: "Saved view version is stale" }, 409));
    vi.stubGlobal("fetch", fetchMock);
    render(<Phase6Workspace credential="k" operator={manager}
      path="/workspace/payment-views" navigate={vi.fn()} />);

    await userEvent.setup().click(await screen.findByRole("button", { name: "Disable" }));

    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe(`/risk/workspace/payment-views/${viewId}/disable`);
    expect(JSON.parse(init.body)).toEqual({ expectedVersion: 4 });
    // The refusal is surfaced as its own recoverable slice, not swallowed into a false success.
    expect(await screen.findByRole("alert")).toHaveTextContent("Saved views unavailable");
    expect(screen.getByText("High risk reviews")).toBeInTheDocument();
  });

test("executing a view re-reads the projection and shows its freshness rather than stored rows",
  async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([savedView()]))
      .mockResolvedValueOnce(json({ data: [{ paymentIntentId: paymentId, amountMinor: 1250,
        currency: "USD", lifecycleStatus: "REQUIRES_REVIEW", fraudAction: "REVIEW",
        riskLevel: "HIGH", riskScoreBasisPoints: 7500, reviewStatus: "OPEN", labelValue: null,
        trafficOrigin: "MERCHANT_API", coverageStatus: "COMPLETE",
        lastEventRecordedAt: "2026-08-01T00:00:01Z" }], nextCursor: null, freshness }));
    vi.stubGlobal("fetch", fetchMock);
    const navigate = vi.fn();
    render(<Phase6Workspace credential="k" operator={manager}
      path="/workspace/payment-views" navigate={navigate} />);

    await userEvent.setup().click(await screen.findByRole("button", { name: "Execute" }));

    expect(fetchMock.mock.calls[1][0]).toMatch(
      new RegExp(`^/risk/workspace/payment-views/${viewId}/execute`));
    await userEvent.setup().click(await screen.findByRole("button", { name: paymentId }));
    expect(navigate).toHaveBeenCalledWith(`/workspace/payments/${paymentId}`);
  });

test("switching operator refetches saved views and never leaves the previous actor's rows on screen",
  async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([savedView({ name: "Manager private view" })]))
      .mockResolvedValueOnce(json([savedView({ id: "50000000-0000-0000-0000-00000000000f",
        name: "Analyst private view" })]));
    vi.stubGlobal("fetch", fetchMock);

    const { rerender } = render(<Phase6Workspace credential="manager-key" operator={manager}
      path="/workspace/payment-views" navigate={vi.fn()} />);
    expect(await screen.findByText("Manager private view")).toBeInTheDocument();

    // A new credential is a new authority. The route is keyed on it, so the previous actor's
    // rows must be replaced by a fresh server answer rather than lingering in component state.
    rerender(<Phase6Workspace credential="analyst-key" operator={analyst}
      path="/workspace/payment-views" navigate={vi.fn()} />);

    expect(await screen.findByText("Analyst private view")).toBeInTheDocument();
    expect(screen.queryByText("Manager private view")).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1].headers["X-Risk-Operator-Key"]).toBe("analyst-key");
    expect(document.body).not.toHaveTextContent("manager-key");
  });

test("a mixed bulk result never claims the whole selection succeeded", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(json({ id: bulkId, action: "ASSIGN_TO_SELF", itemCount: 2,
      lifecycleStatus: "PARTIAL", targetOperatorId: null, requestDigest: "b".repeat(64),
      succeededCount: 1, failedCount: 1, createdAt: "2026-08-01T00:00:00Z",
      completedAt: "2026-08-01T00:00:02Z" }))
    .mockResolvedValueOnce(json({ data: [
      { id: "i-1", reviewCaseId: reviewOne, expectedAssignmentVersion: 0, ordinal: 1,
        lifecycleStatus: "SUCCEEDED", failureCode: null, observedAssignmentVersion: 0,
        finalAssignmentVersion: 1 },
      { id: "i-2", reviewCaseId: reviewTwo, expectedAssignmentVersion: 0, ordinal: 2,
        lifecycleStatus: "STALE", failureCode: "assignment_version_stale",
        observedAssignmentVersion: 3, finalAssignmentVersion: null }],
      nextCursor: null }));
  vi.stubGlobal("fetch", fetchMock);
  const { container } = render(<Phase6Workspace credential="memory-only-risk-key"
    operator={analyst} path="/workspace/bulk" navigate={vi.fn()} />);

  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/Review case UUID/),
    `${reviewOne}:0\n${reviewTwo}:0`);
  await user.click(screen.getByRole("button", { name: "Start durable bulk action" }));

  // The request-level headline is the honest PARTIAL, with per-item truth beside it.
  expect(await screen.findByRole("heading", { name: "Partial" })).toBeInTheDocument();
  expect(screen.getByText(/1 succeeded/)).toBeInTheDocument();
  expect(screen.getByText(/1 failed/)).toBeInTheDocument();
  expect(screen.getByText("Succeeded")).toBeInTheDocument();
  expect(screen.getByText("Stale")).toBeInTheDocument();
  expect(screen.getByText("Assignment version stale")).toBeInTheDocument();
  expect(document.body).not.toHaveTextContent("memory-only-risk-key");
  expect((await axe.run(container)).violations).toEqual([]);
});

test("a viewer cannot start a bulk assignment and is told why", async () => {
  vi.stubGlobal("fetch", vi.fn());
  render(<Phase6Workspace credential="k" operator={viewer} path="/workspace/bulk"
    navigate={vi.fn()} />);

  expect(screen.getByRole("alert")).toHaveTextContent("read-only");
  expect(screen.queryByRole("button", { name: "Start durable bulk action" })).toBeNull();
});

test("only a manager is offered the assign-to-operator action", async () => {
  vi.stubGlobal("fetch", vi.fn());
  const { rerender } = render(<Phase6Workspace credential="k" operator={analyst}
    path="/workspace/bulk" navigate={vi.fn()} />);
  expect(within(screen.getByLabelText("Assignment operation")).getAllByRole("option")
    .map((option) => option.textContent)).toEqual(["ASSIGN_TO_SELF", "RELEASE"]);

  rerender(<Phase6Workspace credential="k" operator={manager} path="/workspace/bulk"
    navigate={vi.fn()} />);
  expect(within(screen.getByLabelText("Assignment operation")).getAllByRole("option")
    .map((option) => option.textContent))
    .toEqual(["ASSIGN_TO_SELF", "RELEASE", "ASSIGN_TO_OPERATOR"]);
});

test("a refused bulk start surfaces the failure instead of a fabricated run", async () => {
  const fetchMock = vi.fn().mockResolvedValue(json({ requestId: "r-2", status: 400,
    error: "bulk_item_count_invalid",
    message: "A bulk review action must contain between 1 and 25 items" }, 400));
  vi.stubGlobal("fetch", fetchMock);
  render(<Phase6Workspace credential="k" operator={analyst} path="/workspace/bulk"
    navigate={vi.fn()} />);

  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/Review case UUID/), `${reviewOne}:0`);
  await user.click(screen.getByRole("button", { name: "Start durable bulk action" }));

  expect(await screen.findByRole("alert")).toHaveTextContent("Bulk action unavailable");
  expect(screen.queryByText(/succeeded ·/)).toBeNull();
});
