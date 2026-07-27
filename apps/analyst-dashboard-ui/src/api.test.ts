import { afterEach, expect, test, vi } from "vitest";
import { executeDeveloperScenarioStep, type DeveloperScenarioStep } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
});

function customerStep(path: string): DeveloperScenarioStep {
  return {
    stepIndex: 0,
    stepKey: "customer",
    operation: "CREATE_CUSTOMER",
    method: "POST",
    path,
    idempotencyKey: "governed-customer-key",
    body: { email: "governed@example.test" },
    fixtureId: null,
    expectedOutcomeCode: null,
    lifecycleStatus: "PENDING",
    resourceType: "CUSTOMER",
    resourceId: null,
    completedAt: null,
  };
}

test.each([
  "//evil.example/customers",
  "/\\evil.example/customers",
  "https://evil.example/customers",
])("rejects hostile scenario path %s before forwarding credentials", async (path) => {
  const fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);

  await expect(executeDeveloperScenarioStep(
    "merchant-secret", "runner-secret", customerStep(path),
  )).rejects.toThrow(/API origin/);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("binds server-provided scenario operations to their governed API path", async () => {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ id: "10000000-0000-0000-0000-000000000001" }), { status: 201 }),
  );
  vi.stubGlobal("fetch", fetchMock);

  await expect(executeDeveloperScenarioStep(
    "merchant-secret", "runner-secret", customerStep("/admin/merchants"),
  )).rejects.toThrow(/governed operation catalogue/);
  expect(fetchMock).not.toHaveBeenCalled();

  await expect(executeDeveloperScenarioStep(
    "merchant-secret", "runner-secret", customerStep("/customers"),
  )).resolves.toBe("10000000-0000-0000-0000-000000000001");
  expect(fetchMock).toHaveBeenCalledWith("/customers", expect.objectContaining({
    credentials: "omit", redirect: "error", referrerPolicy: "no-referrer",
  }));
});
