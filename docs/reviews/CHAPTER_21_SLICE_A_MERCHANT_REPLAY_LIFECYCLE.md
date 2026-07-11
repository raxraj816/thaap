# Chapter 21 Slice A — Merchant Creation Replay Lifecycle

- Parent chapter: 21
- Review provenance: RETROACTIVE_RECOVERY
- Transition table timing: WRITTEN_AFTER_EXISTING_FIX
- Product implementation checkpoint: 2b4b72b
- Independent implementation review: PASS
- Hostile review checkpoint: 303e67d
- Status: VERIFIED
- Escaped findings: ER-0036

This packet is intentionally honest: the first lifecycle-epoch fix existed
before this table. The packet cannot claim pre-implementation provenance. It
defines the complete protocol that a later, separately committed hostile pass
must review.

## Frozen promise

A live merchant-creation key replays exactly one merchant and API key. Reuse
after expiry begins a distinct lifecycle epoch, even when the request and HMAC
pepper are unchanged. No raw idempotency key or API key is persisted.

## Transition table

| Initial state | Concurrent event | Elapsed time | Expected durable result | Exact proof boundary |
| --- | --- | --- | --- | --- |
| No creation record or alias | One valid keyed create | 0 | One `IN_PROGRESS` record becomes `COMPLETED`; one merchant is created; aliases point to that record; raw API key is response-only | H2 API/storage contract plus merchant/API-key hash postconditions |
| `IN_PROGRESS`, live owner lease | Same key and request retries | `< lease` | Retry conflicts; owner token, record, aliases, and merchant count do not change | H2 active-lease contract |
| `IN_PROGRESS`, owner blocked after record lock | Contender retries after wall-clock lease expiry | `> lease` | Contender waits on the PostgreSQL record lock; it cannot run protected creation concurrently | Forced PostgreSQL lock wait and one-merchant postcondition |
| `IN_PROGRESS`, expired and unlocked | Same key and request retries | `>= lease` | Exactly one caller reclaims ownership; stale owner cannot complete | H2 reclaim contract plus PostgreSQL competing-owner postcondition |
| `COMPLETED`, replay window live | Same key and request retries | `< replay TTL` | Same merchant ID and byte-identical API key; no new merchant | Merchant contract with stored hash verification |
| `COMPLETED`, replay window live | Same key, changed name | `< replay TTL` | Conflict; no mutation or credential disclosure | H2 changed-request contract |
| `COMPLETED`, current pepper rotated but original version retained | Same key and request retries | `< replay TTL` | New alias resolves the original record; API key derives with original version and remains identical | H2 rotation contract plus mixed-version PostgreSQL wait |
| `COMPLETED`, original derivation key missing | Application starts | `< replay TTL` | Startup fails before serving requests and names the missing version | Required-key inventory startup contract |
| `COMPLETED` expires after lookup but before response replay | No concurrent writer | crosses exact expiry | Current call returns conflict and creates nothing; a later call may start a new epoch | Fixed-clock before/at/after replay contract |
| `COMPLETED`, replay expired, same pepper and same request | Same external key is reused | `>= replay TTL` | Old record/aliases are retired; new record UUID creates a distinct API key and merchant; old merchant remains valid | `expiredMerchantCreationStartsNewCredentialEpochWithoutPepperRotation` |
| `COMPLETED`, replay expired, old key removed | Same external key is reused under current pepper | `>= replay TTL` | New epoch succeeds without retaining the retired key version | Expired-key-retirement contract |
| Expired record and managed aliases are loaded in one persistence context | Old record is deleted and same aliases are recreated | `>= replay TTL` | Flush/clear removes stale managed aliases before the new epoch commits | Same-pepper expiry contract plus one-record/one-alias database counts |

## Mandatory closure questions

### What can change after this check and before use?

After reservation lookup, the lease can expire or another transaction can
reclaim ownership before merchant creation. The operation transaction therefore
locks the record and validates the owner before inserting the merchant. After a
completed record is returned for replay, wall time can cross exact expiry; the
response path checks expiry again and returns no credential if the window ended.

### What makes a new lifecycle epoch different from a replay?

The immutable merchant-creation record UUID. Live replay uses the same record
UUID and original derivation-key version. Expired reuse deletes the old binding
and reserves a new UUID, so identical external key, request, and pepper cannot
recreate the old merchant credential.

## Primary deterministic proof

- `expiredMerchantCreationStartsNewCredentialEpochWithoutPepperRotation`
- `rollingPepperVersionsShareOneDatabaseIdentityAndReplayOneMerchant`
- `expiredCustomerLeaseCannotBeStolenWhileMutationOwnsTheReservationLock`

Aggregate suite totals are regression context only; they do not close this
packet.

## Independent review result

The review traced every derivation input and record/alias transition from
`2b4b72b`. The decisive hostile partitions are same-pepper expired reuse and
lease expiry while protected work owns the PostgreSQL row lock. Both converge
to the table's durable postconditions; no P0/P1 or frozen-promise P2 issue
remains in this slice.
