# Chapter 21 Slice E — Endpoint Reservation Fencing

- Parent chapter: 21
- Review provenance: RETROACTIVE_RECOVERY
- Transition table timing: WRITTEN_AFTER_EXISTING_FIX
- Product implementation checkpoint: PENDING_COMMIT
- Independent implementation review: PENDING
- Hostile review checkpoint: PENDING_LATER_COMMIT
- Status: NOT_READY
- Escaped findings: ER-0040

## Frozen promise

The endpoint-creation owner locks and validates its durable reservation before
secret generation or endpoint, subscription, and audit mutation. Lease expiry
cannot admit a second live owner while protected work holds that lock.

## Transition table

| Initial state | Concurrent event | Elapsed time | Expected durable result | Exact proof boundary |
| --- | --- | --- | --- | --- |
| No reservation | One keyed create | 0 | One reservation, endpoint, subscription set, audit, and encrypted replay response commit | H2 endpoint creation/storage contract |
| `IN_PROGRESS`, live owner lease | Duplicate caller | `< lease` | Conflict; no second endpoint/secret/audit | Active-lease contract |
| Live owner holds reservation lock and blocks before first endpoint insert | Lease expires; contender retries | `> lease` | Contender waits on reservation row; owner completes; contender replays same endpoint and secret | `liveEndpointCreationLockSurvivesLeaseExpiryBeforeFirstMutationCompletes` plus PostgreSQL wait observation |
| `IN_PROGRESS`, owner process died and lock released | Contender retries | `>= lease` | Contender reclaims with new token and completes one endpoint | PostgreSQL reclaim test |
| Old owner resumes after reclaim | New owner holds/has completed reservation | `> lease` | Old token fails ownership validation before mutation | Owner-token fencing contract |
| `COMPLETED`, same request/key | Retry inside replay window | `< replay TTL` | Same endpoint and raw reveal response; no new audit | Replay/audit-count contract |
| `COMPLETED`, changed request/key binding | Retry inside replay window | `< replay TTL` | Conflict; no mutation | Changed-request contract |
| `COMPLETED`, endpoint secret rotated | Original create request replays | `< replay TTL` | Conflict rather than revealing superseded secret | Rotation-supersession contract |
| Expired creation binding, same pepper, different URL | Same external key reused | `>= replay TTL` | Old binding/aliases retire; new endpoint/secret epoch commits | `expiredWebhookCreationKeyStartsNewEpochWithoutPepperRotation` |
| Different key, identical tenant URL | Concurrent/sequential create | any | Database uniqueness yields explicit conflict; one endpoint stream | V12 unique constraint and API mapping test |
| Same key/URL across different merchants | Concurrent create | any | Independent endpoints and secrets; aliases remain tenant-scoped | Cross-tenant contract |
| Endpoint insert/subscription/audit throws | Operation transaction rolls back | any | No partial endpoint graph; independent reservation is safely released/reclaimable | Rollback and retry contract |

## Mandatory closure questions

### What can change after this check and before use?

The wall-clock lease can expire and a contender can attempt reclaim. The owner
token can also change if the original process lost its lock. Therefore owner
validation occurs only after acquiring the reservation row lock and before the
first protected mutation; the lock, not an earlier wall-clock check, fences the
entire operation transaction.

### What makes a new lifecycle epoch different from a replay?

A live replay resolves to the same creation-record UUID and encrypted response.
After expiry, the old record and tenant-scoped identity aliases retire and a new
record UUID plus fresh random signing secret define a new endpoint-creation
epoch.

## Primary deterministic proof

- `liveEndpointCreationLockSurvivesLeaseExpiryBeforeFirstMutationCompletes`
- `expiredCreationReservationReclaimSerializesAndReplaysOneEndpoint`
- `expiredWebhookCreationKeyStartsNewEpochWithoutPepperRotation`

Aggregate suite totals are regression context only.
