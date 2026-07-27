import { expect, test } from "vitest";
import { paymentsApiProxy } from "../vite.config";

test("proxies every merchant webhook route used by the cockpit to the payments API", () => {
  expect(paymentsApiProxy).toMatchObject({
    "/webhook_endpoints": "http://127.0.0.1:8080",
    "/webhook_deliveries": "http://127.0.0.1:8080",
  });
});
