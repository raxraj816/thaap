import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { DeveloperPortal } from "./DeveloperPortal";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test("renders a credential-free SDK quickstart with no semantic accessibility violations", async () => {
  const navigate = vi.fn();
  const { container } = render(<DeveloperPortal path="/developer/quickstart" navigate={navigate} />);

  expect(screen.getByRole("heading", { name: "Python quickstart" })).toBeVisible();
  expect(screen.getByLabelText("Python SDK quickstart command"))
    .toHaveTextContent("THAAP_MERCHANT_API_KEY");
  expect(document.body).not.toHaveTextContent("sk_live_");
  await userEvent.setup().click(screen.getByRole("button", { name: "Inspect the exact contract" }));
  expect(navigate).toHaveBeenCalledWith("/developer/api");
  expect((await axe.run(container)).violations).toEqual([]);
});

test("loads version and error catalogues with the public version header and safe transport", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({
      defaultVersion: "2026-08-01", versions: [{ version: "2026-08-01",
        introducedAt: "2026-08-01", status: "CURRENT", retirementDate: null,
        compatibility: "Absent header resolves compatibly." }],
    }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ errors: [{ code: "query_timeout",
      httpStatus: 503, retryable: true,
      idempotencyGuidance: "Reuse the identical key and request body.",
      recoveryAction: "retry_same_idempotency_key" }] }), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);

  render(<DeveloperPortal path="/developer/api" navigate={vi.fn()} />);
  expect(await screen.findByText("query_timeout")).toBeVisible();
  expect(screen.getByText("Yes — same idempotency key/body")).toBeVisible();
  expect(screen.getByRole("link", { name: "Download OpenAPI" }))
    .toHaveAttribute("href", "/developer/openapi/2026-08-01");
  for (const call of fetchMock.mock.calls) {
    expect(call[1]).toMatchObject({ credentials: "omit", redirect: "error",
      referrerPolicy: "no-referrer" });
    expect(new Headers(call[1].headers).get("Thaap-Version")).toBe("2026-08-01");
  }
});

test("teaches exact-byte webhook verification and durable deduplication", () => {
  render(<DeveloperPortal path="/developer/webhooks" navigate={vi.fn()} />);
  expect(screen.getByRole("heading", { name: /authenticate exact bytes/i })).toBeVisible();
  expect(screen.getByLabelText("Webhook verification pseudocode"))
    .toHaveTextContent("request.body_bytes");
  expect(screen.getByText(/Persist the event identifier/)).toBeVisible();
});
