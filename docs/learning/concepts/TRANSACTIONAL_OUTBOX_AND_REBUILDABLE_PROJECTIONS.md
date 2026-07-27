# Transactional Outbox And Rebuildable Projections

## Mental Model

A payment API and Kafka are two independent systems. They cannot both commit
through one ordinary PostgreSQL transaction.

The unsafe direct-publish sequence is:

```text
commit payment
  -> publish event
```

If the process crashes between the two steps, the payment exists but the event
does not. Reversing the steps is also unsafe: Kafka may expose an event for a
payment transaction that later rolls back.

The transactional outbox moves the event into the same source-of-truth commit:

```text
PostgreSQL transaction:
  payment/fraud/ledger truth + immutable domain event
commit
  -> later publisher retries event delivery
```

The event is now durable whenever the mutation is durable. Kafka availability
is no longer part of payment correctness.

## Why This Still Is Not Exactly Once

Consider this sequence:

```text
publisher sends event
Kafka acknowledges
publisher crashes before PostgreSQL records the acknowledgement
```

The only safe recovery is to send the same event again. Kafka or the consumer
may therefore observe a duplicate. THAAP should promise what it can prove:

- stable event ID and exact bytes across retry
- at-least-once transport
- one durable consumer effect through inbox deduplication

The consumer commits its inbox entry and projection mutation in one database
transaction. If it crashes after that commit but before acknowledging the Kafka
offset, Kafka redelivers; the completed inbox identity makes the duplicate a
no-op.

This is effectively-once application behavior over at-least-once transport,
not an exactly-once claim across PostgreSQL and Kafka.

## Event, Audit, And Webhook Are Different

| Record | Meaning |
|---|---|
| Domain event | A business fact committed and may update internal read models |
| Audit log | An actor attempted or completed a sensitive action |
| Webhook event | Exact signed callback bytes intended for a merchant receiver |

Treating one as another destroys important truth. A replayed domain event must
not call a processor, recreate ledger entries, resend a webhook, or invent an
actor audit. A webhook retry must preserve receiver deduplication identity. An
audit record must remain useful even when no downstream event consumer exists.

## Immutable Event, Mutable Publication

The semantic event never changes after commit. Publication progress does:

```text
immutable domain event
  -> pending publication
  -> claimed with lease/token
  -> acknowledged | retryable | unknown | blocked
```

Splitting those responsibilities prevents retry counters, leases, or broker
errors from mutating the business fact. A blocked event is retained and made
operable; it is not discarded after an arbitrary retry ceiling.

## Ordering

Global ordering is usually both expensive and misleading. Concurrent database
transactions may allocate numeric IDs in one order and commit in another.

THAAP needs ordering inside one business stream:

```text
PaymentIntent stream
  1 intent created
  2 decision completed
  3 authorization created
  4 capture created
  5 refund created
```

A tenant-qualified stream key selects the Kafka partition, while a unique,
gap-free stream version detects missing or reordered facts. One blocked stream
waits; unrelated streams continue.

Kafka offsets belong to a whole partition, not one domain stream. If one record
is invalid, THAAP durably quarantines and acknowledges that Kafka record without
advancing the affected domain-stream position. Other streams in the partition
may continue, while later records from the affected stream remain held until
the immutable source event is repaired through an explicit recovery path.

## Projection Mental Model

A projection is a derived read model optimized for one product question. It is
not payment, ledger, or fraud authority.

Every projection response needs:

- active generation/version
- source event cutoff or checkpoint
- latest occurrence and processing time
- freshness classification
- coverage and unknown/unlabeled counts
- traffic-origin policy

Without these, an absent row can be mistaken for zero activity and stale data
can be mistaken for current truth.

## Safe Rebuild

Never truncate the live projection and rebuild it in place. Readers would see
partial or internally inconsistent data.

Use generations:

```text
generation A (active, still serving)
  + immutable event source
  -> generation B (shadow rebuild)
  -> catch up B
  -> reconcile counts, versions, digests, business invariants
  -> atomically activate B
  -> retain A as bounded rollback target
```

A rebuild worker is leased and fenced. A stale worker cannot complete or
activate after another worker takes ownership. Activation revalidates the
watermark and reconciliation while holding the projection-definition lock.

Rows that existed before event emission began cannot be fabricated as if they
had historical domain events. A one-time source snapshot instead creates
immutable, safe, digest-bound projection bootstrap facts at an explicit cutoff.
Future rebuilds consume the same bootstrap bytes and then the later real domain
events.

## Payment And Fraud Consequences

- An event is notification of a committed capture, refund, or label; it is not
  permission to repeat the mutation.
- Event replay never creates ledger entries or processor calls.
- Later fraud labels affect outcome analytics only from their durable
  `availableAt`; they cannot leak into decision-time features.
- Synthetic traffic remains server-assigned lineage and is excluded or
  included through an explicit visible policy.
- Aggregated amounts are explainable summaries of ledger-authoritative sources,
  not a second ledger.

## Failure Proof That Matters

Happy-path tests are insufficient. The production claim needs deterministic
proof of:

- domain mutation rollback when event insertion fails
- Kafka acknowledgement followed by database-finalization crash
- publisher lease takeover versus stale finalizer
- duplicate delivery before and after projection commit
- offset acknowledgement failure after a completed inbox
- missing or unsupported stream version
- poison event versus transient database/broker failure
- rebuild crash/resume and stale-worker fencing
- live-write cutoff versus rebuild activation
- partial generation invisibility and reconciliation mismatch rejection
- foreign-tenant event, projection, rebuild, and quarantine substitution
- prohibited data absence in database, Kafka, logs, metrics, audit, API, and UI

Mocks explain control flow. Real PostgreSQL and Kafka certify transaction,
locking, acknowledgement, partition, offset, rebalance, and restart claims.

## Design-Review Summary

The defensible statement is:

> THAAP atomically records domain state and immutable events in PostgreSQL,
> publishes exact stored bytes at least once, applies consumer effects once
> through a transactional inbox, and rebuilds versioned projections from the
> immutable event source before atomic activation.

The statement deliberately avoids claiming global order, broker-level exactly
once, event-sourced payment authority, warehouse scale, or external event API
compatibility.
