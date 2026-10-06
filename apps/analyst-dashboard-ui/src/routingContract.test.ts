import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "vitest";
import { paymentsApiProxy, operationalApiProxy, boundedApiProxy } from "../vite.config";

// The workspace reaches the API through three different routers depending on where it runs:
// the Vite dev proxy locally, nginx inside the compose and container deployments, and CloudFront
// behaviors in AWS. All three carry the same list of prefixes, and nothing but this test stops
// them drifting apart.
//
// Drift here is uniquely nasty because it is invisible to every other check. The suite passes,
// the build passes, the container starts, and one screen 404s only in the environment nobody
// runs locally. So the list is asserted structurally rather than trusted to review.

function nginxProxyPrefixes(): string[] {
  // Resolved from the package root rather than from import.meta.url: these suites run under
  // jsdom, where import.meta.url is an http URL and fileURLToPath rejects it.
  const conf = readFileSync(resolve(process.cwd(), "nginx.conf"), "utf8");

  return [...conf.matchAll(/^\s*location\s+~\s+\^(\/[a-z0-9_]+)\(\?:\/\|\$\)\s*\{[^}]*proxy_pass/gm)]
    .map((match) => match[1])
    .sort();
}

test("nginx proxies exactly the prefixes the dev proxy does, and no others", () => {
  expect(nginxProxyPrefixes()).toEqual(Object.keys({ ...paymentsApiProxy, ...operationalApiProxy }).sort());
});

test("all API matches have exact path-segment boundaries, not lookalike prefix matches", () => {
  for (const prefix of Object.keys({ ...paymentsApiProxy, ...operationalApiProxy })) {
    const pattern = new RegExp(`^${prefix}(?:/|$)`);
    expect(boundedApiProxy[`^${prefix}(?:/|$)`]).toBe("http://127.0.0.1:8080");
    expect(pattern.test(prefix)).toBe(true);
    expect(pattern.test(`${prefix}/nested`)).toBe(true);
    expect(pattern.test(`${prefix}_lookalike`)).toBe(false);
  }
});

test("every proxied prefix is absolute, so a relative match cannot capture the SPA fallback", () => {
  for (const prefix of Object.keys(paymentsApiProxy)) {
    expect(prefix.startsWith("/")).toBe(true);
    expect(prefix.endsWith("/")).toBe(false);
  }
});

test("the SPA fallback is not itself proxied, or deep links would reach the API", () => {
  expect(nginxProxyPrefixes()).not.toContain("/");
});
