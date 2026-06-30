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
| TLS identity | Use the original hostname for SNI and certificate verification |
| Authenticated bytes | Sign timestamp plus the exact persisted UTF-8 payload bytes |
| Response acceptance | Accept only a complete bounded final response head with status `100..599` |
| Redirects | Never follow |
| Limits | 64 KiB payload, 32 KiB response head, bounded resolver pool/queue, total request deadline |
| Retry truth | Persist known no-send, known request-write, or post-dispatch unknown evidence |
| Persistence | Reuse one immutable event payload and timestamp across retries |
| Operations | Bounded logs/metrics; no URL, payload, signature, secret, PII, PAN, or CVV |

## Consequences

- New custom HTTP, crypto, queue, scheduler, or concurrency machinery is blocked
  without the same decision and proof package.
- The Chapter 19 transport has an intentionally narrow feature set. HTTP/2,
  proxying, redirects, decompression, response bodies, and general client
  behavior remain outside it.
- Revisit the transport before shared non-local merchant onboarding, when
  managed egress, proxy policy, mTLS, multi-region routing, or operational
  volume makes a mature client/egress service the safer boundary.
- The closure dossier and readiness gate must name this ADR and verify that the
  protocol table remains covered by matrix evidence.
