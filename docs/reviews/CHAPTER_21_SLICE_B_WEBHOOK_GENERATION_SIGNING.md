# Chapter 21 Slice B — Webhook Generation Authorization And Signing

- Parent chapter: 21
- Review provenance: RETROACTIVE_RECOVERY
- Transition table timing: WRITTEN_AFTER_EXISTING_FIX_BEFORE_ER-0041_FIX
- Product implementation checkpoint: 54862fb
- Independent implementation review: PASS
- Hostile review checkpoint: b5e767d
- Status: VERIFIED
- Escaped findings: ER-0037, ER-0041

## Frozen promise

The secret generation used for an HMAC is valid at the actual cryptographic-use
instant. Rotation and disable cannot cross that use, and no endpoint lock is
held over network I/O.

## Transition table

| Initial state | Concurrent event | Elapsed time | Expected durable result | Exact proof boundary |
| --- | --- | --- | --- | --- |
| Endpoint `ACTIVE`, claim binds current G1 | No concurrent mutation | 0 | Signature timestamp and HMAC use G1; HTTP begins only after transaction commit | Real signer verification plus transaction/transport ordering test |
| Endpoint `ACTIVE`, signer entered with current G1 under row lock | Rotation to G2 starts | any local HMAC duration | Rotation waits; G1 signature completes and verifies before G2 becomes current | `rotationWaitsUntilCurrentGenerationSignatureIsCryptographicallyUsed` with PostgreSQL wait observation |
| Endpoint `ACTIVE`, signer entered under row lock | Disable starts | any local HMAC duration | Disable waits; signature returns before endpoint becomes disabled; network is outside the lock | Forced PostgreSQL signer/disable ordering and endpoint postcondition |
| Claim binds G1; rotation already made G1 previous | Worker signs | `< previous expiry` | Signature uses G1 and verifies | Fixed-clock before-expiry contract |
| Claim binds previous G1 | Worker reaches generation check | `= previous expiry` | No signer and no transport call; stale-generation recovery path owns disposition | Fixed-clock exact-expiry contract |
| Claim binds previous G1 | Worker reaches generation check | `> previous expiry` | No signer and no transport call | Fixed-clock after-expiry contract |
| Claim binds previous G1; worker supplies preparation lower bound | Rotation transaction holds endpoint lock until after G1 expiry | lower bound `< expiry`, lock acquisition/use `>= expiry` | Reject G1 using time captured after lock acquisition immediately before HMAC | `previousGenerationWaitingForEndpointLockIsRejectedAtActualUseExpiry` |
| Claim binds current G1 | Rotation commits G2 before worker obtains endpoint lock | any | G1 is accepted only if still previous and exact use is before expiry; otherwise stale path | PostgreSQL rotation-first partitions with fixed clock |
| Endpoint uses old KEK for current G1 | First delivery signs | 0 | Same raw G1 signs; ciphertext lazily re-encrypts to current KEK in the locked transaction | Ciphertext-version and signature-equivalence contract |
| Ciphertext/AAD is invalid | Worker attempts signing | 0 | No HMAC, no HTTP, terminal secret-integrity/configuration evidence | Cipher tamper and wrong-tenant AAD tests |

## Mandatory closure questions

### What can change after this check and before use?

Generation status, previous-secret expiry, endpoint status, and KEK ciphertext
can change while waiting for the endpoint lock. Therefore the service must
acquire the lock first, capture the signature timestamp second, validate and
decrypt third, and invoke the signer before releasing the lock. A timestamp
captured before lock acquisition is not exact-use proof.

### What makes a new lifecycle epoch different from a replay?

Not applicable to signing-secret derivation: rotation generates fresh random
secret material and a new generation UUID. The analogous identity boundary is
the generation UUID; a claim replay names the same generation, while rotation
creates a different generation.

## Primary deterministic proof

- `rotationWaitsUntilCurrentGenerationSignatureIsCryptographicallyUsed`
- `previousGenerationWaitingForEndpointLockIsRejectedAtActualUseExpiry`

Aggregate suite totals are regression context only.

## Independent review result

Both lock orderings were forced in PostgreSQL. Rotation waits behind a signer
already using current G1; a previous-G1 signer waiting behind the endpoint lock
captures time only after acquisition and is rejected at exact expiry without
invoking HMAC. The lock ends before transport. No blocking authorization/use
partition remains.
