import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export const paymentsApiProxy = {
  "/risk": "http://127.0.0.1:8080",
  "/developer": "http://127.0.0.1:8080",
  "/customers": "http://127.0.0.1:8080",
  "/payment_methods": "http://127.0.0.1:8080",
  "/payment_intents": "http://127.0.0.1:8080",
  "/webhook_endpoints": "http://127.0.0.1:8080",
  "/webhook_deliveries": "http://127.0.0.1:8080",
  "/chargebacks": "http://127.0.0.1:8080",
  "/fraud": "http://127.0.0.1:8080",
  "/ledger": "http://127.0.0.1:8080",
  "/payment_attempts": "http://127.0.0.1:8080",
  "/post_authorization_signals": "http://127.0.0.1:8080",
  "/refunds": "http://127.0.0.1:8080",
  "/reviews": "http://127.0.0.1:8080",
  "/rules": "http://127.0.0.1:8080",
  "/simulator": "http://127.0.0.1:8080",
} as const;

export const operationalApiProxy = {
  "/actuator": "http://127.0.0.1:8080",
  "/admin": "http://127.0.0.1:8080",
  "/internal": "http://127.0.0.1:8080",
  "/v3": "http://127.0.0.1:8080",
} as const;

export const boundedApiProxy = Object.fromEntries(
  Object.entries({ ...paymentsApiProxy, ...operationalApiProxy })
    .map(([prefix, target]) => [`^${prefix}(?:/|$)`, target]),
);

export default defineConfig({
  plugins: [react()],
  server: {
    port: 4173,
    proxy: boundedApiProxy,
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test-setup.ts",
  },
});
