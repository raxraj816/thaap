import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { App } from "./App";
import { DeveloperPortal } from "./DeveloperPortal";

/**
 * C58-UI-001 — the developer routes are the zero-source-reading onboarding surface.
 *
 * These tests drive the whole App rather than the portal in isolation, because the
 * claims being proven are about routing, deep links and the credential boundary,
 * and none of those exist inside a single component. The developer routes are also
 * the one part of the product an unauthenticated visitor can reach, which is exactly
 * why a credential must never appear anywhere on them.
 */

const OPERATOR_CREDENTIAL = "risk-operator-secret-canary";

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
}

beforeEach(() => {
  window.history.pushState(null, "", "/");
  window.localStorage.clear();
  window.sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.pushState(null, "", "/");
});

test("a deep link opens the quickstart without demanding a credential first", async () => {
  window.history.pushState(null, "", "/developer/quickstart");
  renderApp();

  // Onboarding that required an operator login would defeat its own purpose: the
  // developer reading it does not have one yet.
  expect(await screen.findByRole("heading", { name: "Python quickstart" })).toBeVisible();
  expect(screen.queryByRole("button", { name: /connect/i })).not.toBeInTheDocument();
  expect(window.location.pathname).toBe("/developer/quickstart");
});

test("moving between developer routes updates history and back returns to the previous one", async () => {
  window.history.pushState(null, "", "/developer/quickstart");
  renderApp();
  const user = userEvent.setup();

  await user.click(await screen.findByRole("button", { name: "Inspect the exact contract" }));
  await waitFor(() => expect(window.location.pathname).toBe("/developer/api"));

  window.history.back();
  await waitFor(() => expect(window.location.pathname).toBe("/developer/quickstart"));
  expect(await screen.findByRole("heading", { name: "Python quickstart" })).toBeVisible();
});

test("every developer route is reachable directly, the way a shared link arrives", async () => {
  const routes: [string, RegExp][] = [
    ["/developer/quickstart", /Python quickstart/],
    ["/developer/webhooks", /authenticate exact bytes/i],
    ["/developer/scenarios", /Trusted deterministic scenarios/],
  ];
  for (const [path, heading] of routes) {
    window.history.pushState(null, "", path);
    const view = renderApp();
    expect(await screen.findByRole("heading", { name: heading })).toBeVisible();
    view.unmount();
  }
});

test("the quickstart never puts a credential in the DOM, the URL, or browser storage", async () => {
  window.history.pushState(null, "", "/developer/quickstart");
  renderApp();
  await screen.findByRole("heading", { name: "Python quickstart" });

  // The published instruction must teach reading the key from a secret manager into
  // process memory, never pasting a literal into a copyable command.
  const command = screen.getByLabelText("Python SDK quickstart command");
  expect(command).toHaveTextContent("THAAP_MERCHANT_API_KEY");
  expect(command.textContent).toContain("<read from your secret manager>");
  expect(command.textContent).not.toMatch(/sk_(live|test)_/);

  expect(document.body.textContent).not.toContain(OPERATOR_CREDENTIAL);
  expect(window.location.search).toBe("");
  expect(window.location.hash).toBe("");
  expect(window.localStorage.length).toBe(0);
  expect(window.sessionStorage.length).toBe(0);
});

test("the copyable command targets the loopback sandbox and no other host", async () => {
  render(<DeveloperPortal path="/developer/quickstart" navigate={vi.fn()} />);
  const command = screen.getByLabelText("Python SDK quickstart command").textContent ?? "";
  const hosts = [...command.matchAll(/https?:\/\/([^\s"']+)/g)].map((match) => match[1]);
  expect(hosts.length).toBeGreaterThan(0);
  for (const host of hosts) {
    expect(host).toMatch(/^(127\.0\.0\.1|localhost)(:\d+)?$/);
  }
});

test("the quickstart is operable by keyboard alone and lands only on real controls", async () => {
  render(<DeveloperPortal path="/developer/quickstart" navigate={vi.fn()} />);
  const user = userEvent.setup();
  const reachable: string[] = [];

  for (let step = 0; step < 8; step += 1) {
    await user.tab();
    const active = document.activeElement;
    if (!active || active === document.body) break;
    reachable.push(active.tagName);
  }

  // A div wired with onClick would appear here as a DIV and would be invisible to
  // anyone driving the page with a keyboard or a screen reader.
  expect(reachable.length).toBeGreaterThan(0);
  for (const tag of reachable) {
    expect(["A", "BUTTON"]).toContain(tag);
  }
});

test("each developer route renders without an accessibility violation", async () => {
  for (const path of ["/developer/quickstart", "/developer/webhooks", "/developer/scenarios"]) {
    const { container, unmount } = render(<DeveloperPortal path={path} navigate={vi.fn()} />);
    const result = await axe.run(container);
    expect(result.violations, `${path} reported ${result.violations.length} violations`).toEqual([]);
    unmount();
  }
});

test("a failing contract slice does not erase the rest of the developer workflow", async () => {
  // One metadata endpoint is down; the quickstart steps and webhook guidance are
  // static and must keep working, because a developer mid-integration still needs them.
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(
    Object.assign(new Error("catalogue unavailable"), { requestId: "request-77" })));

  render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);
  const alert = await screen.findByRole("alert");
  expect(within(alert).getByText(/Contract metadata unavailable/)).toBeVisible();

  const retry = within(alert).getByRole("button", { name: "Retry metadata" });
  retry.focus();
  expect(document.activeElement).toBe(retry);
  expect(screen.getByRole("link", { name: "Download OpenAPI" })).toBeVisible();
});

test("the webhook guide teaches verification before parsing, in that order", async () => {
  render(<DeveloperPortal path="/developer/webhooks" navigate={vi.fn()} />);
  const pseudocode = screen.getByLabelText("Webhook verification pseudocode").textContent ?? "";

  // Re-serialising a parsed body before verifying is the single most common way a
  // correct signature is made to fail and an incorrect one to pass.
  expect(pseudocode).toContain("request.body_bytes");
  expect(pseudocode.indexOf("verify_hmac")).toBeLessThan(pseudocode.indexOf("apply_once"));
  expect(pseudocode).toContain("reject_if_stale");
});

test("no internal, actuator or admin route is advertised to a developer", async () => {
  for (const path of ["/developer/quickstart", "/developer/api", "/developer/webhooks",
    "/developer/scenarios"]) {
    const { container, unmount } = render(<DeveloperPortal path={path} navigate={vi.fn()} />);
    const markup = container.innerHTML;
    for (const forbidden of ["/internal/", "/actuator", "/admin/", "/platform/"]) {
      expect(markup, `${path} exposed ${forbidden}`).not.toContain(forbidden);
    }
    unmount();
  }
});
