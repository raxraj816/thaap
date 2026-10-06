import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import test from "node:test";

const root = fileURLToPath(new URL("../", import.meta.url));
const locals = readFileSync(`${root}infra/terraform/locals.tf`, "utf8");
const blocks = [...locals.matchAll(/(?:api|operational)_path_prefixes\s*=\s*\[([\s\S]*?)\]/g)];
const prefixes = blocks.flatMap(match => [...match[1].matchAll(/"(\/[a-z0-9_]+)"/g)].map(item => item[1]));
const source = readFileSync(`${root}infra/terraform/edge-request.js.tftpl`, "utf8")
  .replace("${jsonencode(prefixes)}", JSON.stringify(prefixes));
const context = vm.createContext({});
vm.runInContext(source, context);

function run(uri, method = "GET", headers = {}) {
  const request = { uri, method, headers, querystring: { filter: { value: "unmodified%2Fbytes" } } };
  const event = { request, viewer: { ip: "192.0.2.10" } };
  assert.equal(context.handler(event), request);
  assert.deepEqual(request.querystring, { filter: { value: "unmodified%2Fbytes" } });
  return request;
}

test("every public contract route and operational subtree remains an API request", () => {
  const contract = JSON.parse(readFileSync(`${root}openapi/thaap-public-api-2026-08-01.yaml`, "utf8"));
  assert.equal(prefixes.length, 20);
  for (const uri of [...Object.keys(contract.paths), ...prefixes.flatMap(p => [p, `${p}/`, `${p}/nested`])]) {
    for (const method of ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]) {
      assert.equal(run(uri, method).uri, uri);
    }
  }
});

test("navigation rewrites are request-side only, with no success rewrite for missing assets", () => {
  for (const uri of ["/", "/workspace/investigation", "/developer-docs", "/risky", "/risk_lookalike"]) {
    for (const method of ["GET", "HEAD"]) assert.equal(run(uri, method).uri, "/index.html");
    assert.equal(run(uri, "POST").uri, uri);
  }
  for (const uri of ["/assets/missing.js", "/assets/missing", "/favicon.ico", "/robots.txt", "/index.html"]) {
    assert.equal(run(uri).uri, uri);
  }
  assert.doesNotMatch(readFileSync(`${root}infra/terraform/cloudfront.tf`, "utf8"), /custom_error_response\s*\{/);
});

test("forged proxy metadata is removed while credentials, host and query survive", () => {
  for (const forged of ["1.2.3.4", "::1", "1.2.3.4:99, 5.6.7.8", "\"evil\"", "unknown"]) {
    const headers = {
      forwarded: { value: `for=${forged};host=evil.test;proto=http` },
      "x-forwarded-for": { value: forged },
      "x-forwarded-host": { value: "evil.test" },
      "x-forwarded-prefix": { value: "/evil" },
      "x-forwarded-port": { value: "99" },
      "x-forwarded-ssl": { value: "on" },
      host: { value: "workspace.example.test" },
      "x-api-key": { value: "fixture-credential" },
      "x-risk-operator-key": { value: "fixture-operator" },
      "idempotency-key": { value: "fixture-idempotency" },
    };
    const expected = structuredClone(headers);
    const result = run("/payment_intents", "POST", headers);
    assert.equal(result.headers["x-forwarded-for"].value, "192.0.2.10");
    for (const header of ["forwarded", "x-forwarded-host", "x-forwarded-prefix", "x-forwarded-port", "x-forwarded-ssl"]) {
      assert.equal(result.headers[header], undefined);
    }
    for (const header of ["host", "x-api-key", "x-risk-operator-key", "idempotency-key"]) {
      assert.deepEqual(result.headers[header], expected[header]);
    }
  }
});

test("function only writes CloudFront-permitted viewer-request headers", () => {
  const request = run("/risk");
  assert.deepEqual(Object.keys(request.headers), ["x-forwarded-for"]);
  const event = { request: { uri: "/risk", method: "GET", headers: {} }, viewer: { ip: "2001:db8::1" } };
  assert.equal(context.handler(event).headers["x-forwarded-for"].value, "2001:db8::1");
});
