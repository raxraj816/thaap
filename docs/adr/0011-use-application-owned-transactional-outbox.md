# ADR-0011: Use An Application-Owned Transactional Outbox And Exact Canonical Event Bytes

Date: 2026-07-27
Status: Accepted

## Context

Phase 5 must make a committed payment or fraud fact publishable without
creating a second authority for payment, ledger, fraud, audit, or webhook
truth. PostgreSQL and Kafka do not share one ordinary transaction. Publishing
directly after a domain commit can lose the event when the process crashes;
publishing before commit can expose an event for state that later rolls back.

The event representation is also a security and lineage boundary. Jackson's
normal serialization and property-order options do not by themselves certify
RFC 8785 behavior across nested keys, Unicode, I-JSON number restrictions, and
exact primitive representation. A publisher that reconstructs an envelope on
retry can therefore change bytes even when the semantic payload appears equal.

The original Phase 5 plan named `payloadSha256` but did not separately bind all
envelope fields and the exact Kafka record value. Payload integrity alone does
not detect a changed tenant, stream, version, time, actor, origin, correlation,
or causation field. Embedding a complete-record digest inside the bytes it
hashes would create a self-referential representation.

## Alternatives Considered

### Publish directly to Kafka inside or beside the domain transaction

Rejected. Kafka cannot participate in the existing PostgreSQL transaction with
the required domain, ledger, audit, webhook, and idempotency writes. Transaction
duration and broker availability would also become payment-path correctness
dependencies without eliminating the acknowledgement-before-finalization
duplicate.

### Use Debezium or raw change-data capture

Rejected for Phase 5. CDC can transport table changes but does not define the
allowlisted semantic event, stream root, actor/origin lineage, schema version,
privacy contract, or stable replay bytes. It would export persistence shape and
still require an application-owned translation and compatibility boundary.

### Treat Jackson ordering as canonical JSON

Rejected. Sorting object properties and removing whitespace is narrower than
RFC 8785/JCS and leaves primitive, Unicode, nested-order, and I-JSON edge cases
without an independent compatibility contract.

### Write a THAAP canonicalizer

Rejected under ADR-0009. Canonical number and Unicode representation is generic
protocol infrastructure, not THAAP's differentiating payment-risk logic.

## Decision

THAAP uses an application-owned transactional outbox because the application
owns semantic event meaning and the PostgreSQL transaction containing source
truth. One domain transaction writes the source mutation, ledger/audit/webhook
evidence where applicable, immutable event, pending publication, and completed
idempotency response. Any failure rolls the whole transaction back. Kafka is
never contacted before that commit.

Jackson remains the strict tree/DTO construction boundary: producers build only
schema-allowlisted values and reject duplicate properties, non-I-JSON input,
non-finite numbers, prohibited fields, and representation-unsafe values. Exact
canonical bytes are produced through the RFC 8785 Appendix G Java
implementation published as
`io.github.erdtman:java-json-canonicalization`, isolated behind a small THAAP
adapter. Chapter 44 pins the released dependency, records its license and
software-composition evidence, and runs the RFC/golden corpus. If that
dependency cannot pass the pinned toolchain, maintenance, or security review,
this ADR and the Chapter 44 matrix reopen before implementation selects another
maintained RFC 8785 implementation; a handwritten fallback is prohibited.

The canonical payload permits JSON booleans, null according to each schema's
explicit policy, strings valid under I-JSON, and numeric forms explicitly
approved by the schema. Payment amounts remain integer minor units. Integers
outside the interoperable I-JSON range, arbitrary decimal money, timestamps,
and identifiers are encoded as schema-defined strings rather than rounded
binary floating-point numbers. Timestamps are UTC strings normalized to
PostgreSQL microsecond precision before canonicalization.

For each event, the writer produces and persists these immutable boundaries:

1. `payload_bytes`: RFC 8785/JCS-compatible UTF-8 bytes for the allowlisted
   versioned payload.
2. `payload_sha256`: lowercase hexadecimal SHA-256 of `payload_bytes`.
3. `event_record_bytes`: RFC 8785/JCS-compatible UTF-8 bytes for the complete
   envelope plus the payload value and `payloadSha256`.
4. `event_record_sha256`: lowercase hexadecimal SHA-256 of
   `event_record_bytes`, stored beside and deliberately excluded from the bytes
   it hashes.

The immutable database columns also retain queryable envelope values, but an
insertion succeeds only when those values, both exact byte sequences, and both
digests agree. PostgreSQL guards reject UPDATE or DELETE of immutable event
identity, stream position, bytes, or digests. Kafka publication uses
`event_record_bytes` as the exact record value and never reserializes a domain
entity or stored JSON tree. A Kafka header may repeat
`event_record_sha256` for early corruption detection, but consumers do not
trust a header over recomputation and the immutable source record.

The full event bytes do not contain `eventRecordSha256`; consumers obtain the
expected digest from trusted persisted source metadata during Phase 5 internal
recovery or a bounded transport header that must agree with the source. This
avoids self-reference while binding the entire transmitted value. The envelope
does contain `payloadSha256`, allowing a consumer to prove both the nested
payload boundary and complete-record boundary before projection work begins.

## Boundary Table

| Boundary | Decision |
| --- | --- |
| Source authority | PostgreSQL domain, ledger, fraud, audit, and webhook rows remain authoritative; a domain event reports a committed fact only |
| Atomicity | Source mutation, immutable event, pending publication, audit where required, and idempotency completion commit or roll back together |
| Broker timing | Kafka is contacted only after PostgreSQL commit and outside the domain transaction |
| Event identity | Random event UUID plus tenant-qualified stream type, stream ID, and gap-free stream version are allocated once |
| Payload schema | Closed event type/version catalogue and allowlisted fields; unknown producer fields, unrestricted metadata, and prohibited sensitive values are rejected |
| Payload bytes | RFC 8785/JCS-compatible UTF-8 produced by the selected external canonicalizer from a strict Jackson-built tree |
| Payload digest | SHA-256 of exact payload bytes; stored in the envelope and immutable database metadata |
| Full record bytes | Canonical complete envelope plus payload and payload digest, persisted once as the exact Kafka value |
| Full record digest | SHA-256 of exact full record bytes, stored beside rather than inside its own digest input |
| Consumer integrity | Recompute full-record and payload digests, then validate topic key, envelope, schema, tenant, stream, and version before projection work |
| Duplicate keys | Strict parsing and producer construction reject duplicate object members before canonicalization |
| Unicode | Only valid I-JSON strings; malformed UTF-8 and lone surrogates fail closed; normalization is never silently invented |
| Numbers | No NaN or infinities; money uses integer minor units; unsafe integers, precise decimals, timestamps, and identifiers use schema-defined strings |
| Timestamps | UTC, explicit meaning, and PostgreSQL microsecond precision before canonicalization |
| Null and absence | Versioned schema defines the distinction; optional-field evolution cannot silently change null meaning |
| Size limits | Bounded payload, envelope, nesting depth, string length, collection length, and canonical output size are checked before persistence and again before consumption |
| Ordering | One locked tenant-qualified stream boundary allocates versions; no surrogate sequence is described as global commit order |
| Retry | Publisher retry sends the same stored event ID, bytes, and digests; it never reconstructs from current state |
| Unknown acknowledgement | At-least-once retry may duplicate transport; consumer inbox deduplication owns one durable projection effect |
| Persistence | Immutable byte and digest columns plus append-only publication attempts; mutable leases and retry state remain separate |
| Privacy | PAN, CVV, credentials, note plaintext, PII, raw device/session identities, idempotency material, exception text, and unrestricted metadata are prohibited |
| Operations | Backlog, age, retry, unknown, blocked, and lease recovery use closed bounded dimensions without payload, tenant, event, trace, or credential labels |
| Reference fixtures | RFC 8785 samples, cross-language golden bytes, representation partitions, database precision, retained migration, and exact retry fixtures are executable protocol evidence |
| Replacement trigger | Revisit when multiple independent/external consumers require Schema Registry or another encoding, measured polling requires CDC, the selected JCS dependency fails maintenance/security policy, or topic/version scale requires a governed cutover |

## Consequences

- Kafka unavailability cannot roll back an already committed merchant operation;
  the pending immutable event remains recoverable.
- The honest transport promise is at least once. Inbox/projection atomicity, not
  producer marketing language, makes a duplicate harmless.
- Storage includes payload bytes, full event bytes, and two SHA-256 values. The
  duplication is intentional evidence for representation, retry, and consumer
  integrity.
- PostgreSQL `jsonb` may support bounded queries or schema checks, but it cannot
  replace exact stored bytes because it does not preserve a wire
  representation.
- A schema or canonicalization change that alters bytes requires a new event
  version and compatibility proof; it cannot rewrite historical events.
- Chapter 44 owns the adapter, schema catalogue, golden corpus, storage guards,
  and atomic writer. Chapters 45 and 46 may only add producer mappings that
  conform to this contract.

## References

- [RFC 8785: JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785.html)
- [RFC-listed Java JCS implementation](https://github.com/erdtman/java-json-canonicalization)
- [Maven Central coordinate](https://central.sonatype.com/artifact/io.github.erdtman/java-json-canonicalization)
