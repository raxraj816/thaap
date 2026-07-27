# ADR-0009: Minimize Custom Infrastructure And Prove Required Protocol Boundaries

Date: 2026-06-30
Status: Accepted

## Context

THAAP differentiates through explainable risk decisions, replayable fraud
lineage, analyst feedback, and auditable payment truth. It does not
differentiate by owning generic HTTP, cryptographic, queueing, scheduling, or
concurrency infrastructure.

Chapter 19 nevertheless needs one unusual outbound boundary: validate every DNS
answer against tenant and network policy, connect to one already-approved IP
address to prevent DNS rebinding, and preserve the original URI hostname for
HTTP authority and TLS identity. The JDK HTTP client does not expose that exact
per-request connect-address boundary. Replacing it with a large framework solely
for this local simulator slice would add a second policy abstraction without
removing the need to prove address pinning.

## Decision

THAAP defaults to mature libraries and managed infrastructure for generic
capabilities. Custom infrastructure requires:

1. an accepted ADR explaining the differentiating constraint;
2. a bounded ownership surface;
3. a protocol boundary table;
4. hostile executable tests at the real boundary;
5. a named replacement trigger.

Chapter 19 retains the small `SocketWebhookHttpTransport` boundary for exact
address pinning. It does not implement a general HTTP client: it supports one
POST request, no redirects, no response body consumption, bounded response
heads, bounded wall-clock time, and local-loopback HTTP or allowlisted HTTPS.

| Boundary | Chapter 19 contract |
| --- | --- |
| Destination identity | Connect only to an address returned by the validated, all-address DNS decision |
| HTTP authority | Preserve the original URI hostname and port; bracket only IPv6 literals |
| Request target | Serialize the configured path and query through the URI ASCII representation so Unicode is UTF-8 percent-encoded and existing escapes remain stable |
| TLS identity | Use the original hostname for SNI and certificate verification |
| Authenticated bytes | After dispatch preparation, sign a fresh timestamp plus the exact persisted UTF-8 payload bytes |
| Response acceptance | Accept only a complete bounded final HTTP/1.0 or HTTP/1.1 response head with exact status grammar, status `100..599`, ASCII HTTP-token field names, exact colon separators, and control-safe field values |
| Redirects | Never follow |
| Limits | 64 KiB payload rechecked at execution/transport; one cumulative 32 KiB response-head budget including status lines, fields, CRLFs, and all informational/final sections; bounded resolver pool/queue; connect/request durations that remain positive bounded integer milliseconds; total request deadline |
| Retry truth | Persist known no-send, known request-write, or post-dispatch unknown evidence |
| Persistence | Reuse one immutable event payload and timestamp across retries |
| Operations | Bounded logs/metrics; no URL, payload, signature, secret, PII, PAN, or CVV |

### Chapter 42 verification-response extension

Chapter 42 may extend the same final-response-head parser for endpoint
verification. The extension remains deliberately narrower than a general
header client: `WebhookHttpResponse` may return the status code plus one
optional `THAAP-Webhook-Verification` token and nothing else.

| Boundary | Chapter 42 verification contract |
| --- | --- |
| Proof meaning | Possession of the unpredictable token from THAAP's signed, endpoint-generation-bound challenge; HTTPS authenticates the receiver outside loopback simulator mode |
| Field identity | HTTP field name is case-insensitive and must occur exactly once in the final response head; any duplicate in any casing is invalid |
| Field value | Remove only leading/trailing HTTP SP/HTAB optional whitespace, then require exactly 43 ASCII base64url characters `[A-Za-z0-9_-]`; empty, padded, quoted, comma-joined, Unicode, control, or folded values are invalid |
| Response sequence | Informational-response fields are discarded; only the final head can supply proof; verification additionally requires a final `2xx` status |
| Representation boundary | The token must be in the named response field; bodies are never consumed, decoded, decompressed, parsed, or treated as proof |
| Limits and timeout | Existing 8 KiB line, cumulative 32 KiB head, informational-count, socket timeout, and total deadline limits apply unchanged |
| Redirect/address/TLS | Redirects remain final non-success responses; validated all-address policy, pinned connect address, original authority, SNI, and certificate identity remain unchanged |
| Persistence | Raw token exists only in request/response transit and process memory; PostgreSQL stores a SHA-256 hash and terminal closed result, never response bytes |
| Operations | Logs, metrics, audit, errors, and API/UI responses exclude raw token, hash, headers, URL, signature, and payload |
| Replacement trigger | The existing managed-egress/mTLS/multi-region trigger still applies; any second response field, response body, content coding, authentication scheme, or general HTTP method requires replacing or separately re-justifying this transport |

## Consequences

- New custom HTTP, crypto, queue, scheduler, or concurrency machinery is blocked
  without the same decision and proof package.
- The Chapter 19 transport has an intentionally narrow feature set. HTTP/2,
  proxying, redirects, decompression, response bodies, and general client
  behavior remain outside it.
- Chapter 42's single verification field does not broaden the transport into a
  header collection API. Ambiguous duplicates and malformed token
  representations fail the entire exchange.
- Revisit the transport before shared non-local merchant onboarding, when
  managed egress, proxy policy, mTLS, multi-region routing, or operational
  volume makes a mature client/egress service the safer boundary.
- The closure dossier and readiness gate must name this ADR and verify that the
  protocol table remains covered by matrix evidence.
