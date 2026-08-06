import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { DeveloperPortal } from "./DeveloperPortal";

const versionCatalogue = {
  defaultVersion: "2026-08-01",
  versions: [{ version: "2026-08-01", status: "CURRENT", introducedAt: "2026-08-01",
    compatibility: "ADDITIVE_ONLY" }],
};

const errorCatalogue = {
  errors: [
    { code: "payment_intent_not_confirmable", httpStatus: 409, retryable: false,
      recoveryAction: "correct_the_request_before_retry",
      message: "PaymentIntent cannot be confirmed in its current state" },
    { code: "processor_timeout", httpStatus: 503, retryable: true,
      recoveryAction: "retry_with_same_idempotency_key_after_retry_after",
      message: "The processor did not answer in time" },
  ],
};

function routed(versions: unknown, errors: unknown) {
  return vi.fn().mockImplementation((url: string) => {
    if (url.includes("api-versions")) {
      return versions instanceof Error ? Promise.reject(versions)
        : Promise.resolve(new Response(JSON.stringify(versions), { status: 200 }));
    }
    if (url.includes("error-catalog")) {
      return errors instanceof Error ? Promise.reject(errors)
        : Promise.resolve(new Response(JSON.stringify(errors), { status: 200 }));
    }
    return Promise.resolve(new Response("{}", { status: 200 }));
  });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test("the contract page publishes the version lifecycle and the stable error catalogue",
  async () => {
    vi.stubGlobal("fetch", routed(versionCatalogue, errorCatalogue));
    const { container } = render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);

    expect(await screen.findByText("CURRENT")).toBeInTheDocument();
    expect(screen.getAllByText("2026-08-01")).not.toHaveLength(0);
    expect(screen.getByText("ADDITIVE_ONLY")).toBeInTheDocument();

    // Both codes and their retry semantics are readable without parsing a message.
    expect(screen.getByText("payment_intent_not_confirmable")).toBeInTheDocument();
    expect(screen.getByText("processor_timeout")).toBeInTheDocument();
    expect((await axe.run(container)).violations).toEqual([]);
  });

test("retryability is stated in words rather than left to the reader to infer", async () => {
  vi.stubGlobal("fetch", routed(versionCatalogue, errorCatalogue));
  render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);

  // A bare boolean would make an integrator guess whether they may reuse the key.
  expect(await screen.findByText("Yes — same idempotency key/body")).toBeInTheDocument();
  expect(screen.getByText("No")).toBeInTheDocument();
  expect(screen.getByText("retry with same idempotency key after retry after"))
    .toBeInTheDocument();
});

test("contract metadata is fetched with no credential and a no-referrer policy", async () => {
  const fetchMock = routed(versionCatalogue, errorCatalogue);
  vi.stubGlobal("fetch", fetchMock);
  render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);

  await screen.findByText("CURRENT");
  for (const [, init] of fetchMock.mock.calls) {
    // This surface is deliberately open; sending a credential to it would both be pointless
    // and put an operator key into a page any integrator can open.
    expect(init?.headers ?? {}).not.toHaveProperty("X-Risk-Operator-Key");
    expect(init?.headers ?? {}).not.toHaveProperty("X-Api-Key");
    expect(init?.credentials).toBe("omit");
    expect(init?.referrerPolicy).toBe("no-referrer");
    expect(init?.redirect).toBe("error");
  }
});

test("the OpenAPI download stays same-origin and carries no referrer", async () => {
  vi.stubGlobal("fetch", routed(versionCatalogue, errorCatalogue));
  render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);

  const link = await screen.findByRole("link", { name: "Download OpenAPI" });
  expect(link).toHaveAttribute("href", "/developer/openapi/2026-08-01");
  expect(link.getAttribute("href")).not.toMatch(/^https?:/);
  expect(link).toHaveAttribute("referrerPolicy", "no-referrer");
});

test("unavailable contract metadata is an explicit retryable failure, not a blank page",
  async () => {
    const failure = Object.assign(new Error("contract metadata unavailable"),
      { status: 503, requestId: "req-77" });
    vi.stubGlobal("fetch", routed(failure, errorCatalogue));
    render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Contract metadata unavailable");
    expect(alert).toHaveTextContent("req-77");
    expect(screen.getByRole("button", { name: "Retry metadata" })).toBeInTheDocument();
  });

test("retrying after a failure recovers the page rather than requiring a reload", async () => {
  const failure = Object.assign(new Error("temporary"), { status: 503 });
  let attempt = 0;
  const fetchMock = vi.fn().mockImplementation((url: string) => {
    attempt += 1;
    if (attempt <= 2) return Promise.reject(failure);
    const body = url.includes("api-versions") ? versionCatalogue : errorCatalogue;
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
  });
  vi.stubGlobal("fetch", fetchMock);
  render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);

  await screen.findByRole("alert");
  await userEvent.setup().click(screen.getByRole("button", { name: "Retry metadata" }));

  expect(await screen.findByText("CURRENT")).toBeInTheDocument();
  expect(screen.queryByRole("alert")).toBeNull();
});

test("the contract page renders no internal or platform route", async () => {
  vi.stubGlobal("fetch", routed(versionCatalogue, errorCatalogue));
  render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);

  await screen.findByText("CURRENT");
  const body = document.body.textContent ?? "";
  for (const internal of ["/internal/", "/actuator", "/admin/merchants", "/platform/"]) {
    expect(body).not.toContain(internal);
  }
});

test("the contract page is fully traversable by keyboard alone", async () => {
  vi.stubGlobal("fetch", routed(versionCatalogue, errorCatalogue));
  render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);
  await screen.findByText("CURRENT");

  // An integrator reading a contract page with a screen reader or no mouse must reach every
  // control. Tabbing is the check that a div-as-button would fail.
  const user = userEvent.setup();
  const reachable: string[] = [];
  for (let step = 0; step < 12; step++) {
    await user.tab();
    const active = document.activeElement as HTMLElement | null;
    if (!active || active === document.body) break;
    reachable.push(`${active.tagName}:${(active.textContent ?? "").trim().slice(0, 24)}`);
  }

  expect(reachable.length).toBeGreaterThan(0);
  // Every focus stop is a genuinely interactive element, never a decorative container.
  for (const stop of reachable) {
    expect(stop.split(":")[0]).toMatch(/^(A|BUTTON|INPUT|SELECT|TEXTAREA)$/);
  }
  expect(reachable.some((stop) => stop.includes("Download OpenAPI"))).toBe(true);
});

test("the retry control is reachable and operable from the keyboard after a failure",
  async () => {
    const failure = Object.assign(new Error("temporary"), { status: 503 });
    let attempt = 0;
    vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string) => {
      attempt += 1;
      if (attempt <= 2) return Promise.reject(failure);
      const body = url.includes("api-versions") ? versionCatalogue : errorCatalogue;
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    }));
    render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);
    await screen.findByRole("alert");

    const retry = screen.getByRole("button", { name: "Retry metadata" });
    retry.focus();
    expect(document.activeElement).toBe(retry);
    await userEvent.setup().keyboard("{Enter}");

    // Recovering from an outage must not require a mouse.
    expect(await screen.findByText("CURRENT")).toBeInTheDocument();
  });

test("the contract tables stay readable on a narrow viewport without horizontal page scroll",
  async () => {
    vi.stubGlobal("fetch", routed(versionCatalogue, errorCatalogue));
    const { container } = render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);
    await screen.findByText("CURRENT");

    // Wide tables must scroll inside their own container. Without this the whole page scrolls
    // sideways on a phone and the first column, which carries the error code, is what gets lost.
    const tables = container.querySelectorAll("table");
    expect(tables.length).toBeGreaterThan(0);
    tables.forEach((table) => {
      expect(table.closest(".table-wrap"))
        .not.toBeNull();
    });
  });
