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
} as const;

export default defineConfig({
  plugins: [react()],
  server: {
    port: 4173,
    proxy: paymentsApiProxy,
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test-setup.ts",
  },
});
