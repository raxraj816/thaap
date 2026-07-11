# Chapter 21 Slice C — Webhook Retry And Error Classification

- Parent chapter: 21
- Review provenance: RETROACTIVE_RECOVERY
- Transition table timing: WRITTEN_AFTER_EXISTING_FIX_BEFORE_ER-0042_FIX
- Product implementation checkpoint: PENDING_COMMIT
- Independent implementation review: PENDING
- Hostile review checkpoint: PENDING_LATER_COMMIT
- Status: REOPENED
- Escaped findings: ER-0038, ER-0042

## Frozen promise

Failure disposition reflects delivery truth. Permanent pre-send failures are
terminal, recoverable known-no-send failures converge, and ambiguous possible
sends use lease recovery. An active endpoint never loses a delivery solely
because a claim-bound secret snapshot became stale before any HTTP request.

## Transition table

| Initial state | Concurrent event | Elapsed time | Expected durable result | Exact proof boundary |
| --- | --- | --- | --- | --- |
| Prepared claim, generation remains valid | Transport returns 2xx | bounded request timeout | Attempt and delivery succeed | Executor/finalization contract |
| Prepared claim, generation becomes stale | Rotation/grace expiry occurs before signing | any | No signer/HTTP; recover against current durable generation without losing delivery | `expiredClaimGenerationRetriesAndNextClaimBindsCurrentSecret` |
| Prepared claim on attempt 1–4, generation stale | Endpoint remains active | before HTTP | Known-no-send evidence and a due recovery path | H2 delivery state/attempt postconditions |
| Prepared claim on final allowed attempt, generation stale | Endpoint remains active | before HTTP | Delivery must still get one current-generation send opportunity; stale internal snapshot cannot exhaust it | **Missing before ER-0042 fix:** final-attempt stale-generation contract |
| Prepared claim, local signer throws | No HTTP request exists | 0 | `SIGNING_FAILED`, known-no-send retry subject to bounded operational retry policy | Executor unit classification test |
| Prepared claim, database/runtime fails before signer returns | Send cannot be authoritatively finalized | lease duration | Leave claim for lease recovery; do not manufacture terminal truth | Executor unexpected-runtime test plus lease recovery contract |
| Target DNS resolution fails safely | No socket opened | resolver deadline | Retryable known-no-send | Target resolution contract |
| Target violates policy | No socket opened | 0 | Terminal `TARGET_NOT_ALLOWED` | Target-policy contract |
| Payload integrity fails | No socket opened | 0 | Terminal `PAYLOAD_INTEGRITY_FAILED` | Payload hash contract |
| Transport fails with `requestSent=false` | No request emitted | request timeout | Retryable known-no-send until transport retry budget ends | Transport/finalization contract |
| Transport fails with `requestSent=null` | Outcome ambiguous | lease/finalization time | `OUTCOME_UNKNOWN`; retry or exhaust according to at-least-once policy | Ambiguous-send and lease-recovery tests |
| Any retryable failure | Endpoint disabled before finalization | any | Attempt records safe evidence; delivery exhausts as `ENDPOINT_DISABLED` | Forced endpoint share/exclusive lock ordering |
| Finalization receives stale claim token | Another recovery already won | any | No second state transition or attempt rewrite | Stale-finalization contract |

## Mandatory closure questions

### What can change after this check and before use?

Endpoint status, secret generation, lease ownership, retry budget, and transport
send truth can change between preparation and finalization. Each disposition is
therefore decided at the transaction that owns the relevant row, and stale
generation must be resolved before transport rather than treated as a generic
secret failure.

### What makes a new lifecycle epoch different from a replay?

Not applicable to secret derivation. For delivery recovery, the durable claim
token and attempt evidence distinguish replay of one ownership epoch from a new
claim. A stale token cannot finalize a later claim.

## Primary deterministic proof

- `expiredClaimGenerationRetriesAndNextClaimBindsCurrentSecret`
- Required new proof: stale generation on the retry ceiling still receives one
  current-generation send opportunity.

Aggregate suite totals are regression context only.
