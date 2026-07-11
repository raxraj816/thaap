# Chapter 21 Slice D — KEK Startup Inventory

- Parent chapter: 21
- Review provenance: RETROACTIVE_RECOVERY
- Transition table timing: WRITTEN_AFTER_EXISTING_FIX
- Product implementation checkpoint: PENDING_COMMIT
- Independent implementation review: PENDING
- Hostile review checkpoint: PENDING_LATER_COMMIT
- Status: NOT_READY
- Escaped findings: ER-0039

## Frozen promise

Startup fails before serving any reachable webhook capability when persisted
state may request a KEK version absent from the configured keyring. Worker mode
does not suppress administration/replay readiness.

## Transition table

| Initial state | Concurrent event | Elapsed time | Expected durable result | Exact proof boundary |
| --- | --- | --- | --- | --- |
| Active endpoint current ciphertext uses configured KEK | Startup validation | 0 | Startup succeeds | Configuration contract plus repository inventory |
| Active endpoint current ciphertext uses missing KEK | Startup validation | 0 | Startup fails and names version | Enabled-mode startup test |
| Active endpoint unexpired previous ciphertext uses missing KEK | Startup validation | `< overlap expiry` | Startup fails | Previous-ciphertext inventory contract |
| Previous ciphertext reaches exact expiry | Startup validation | `= overlap expiry` | Version is no longer required by previous state | Fixed-clock exact-expiry repository query |
| Completed creation replay ciphertext uses missing KEK and generation still matches endpoint | Startup validation | `< replay TTL` | Startup fails | Replay-ciphertext inventory contract |
| Creation replay expires or is superseded by rotation | Startup validation | `>= replay TTL` or generation mismatch | Obsolete replay KEK is not required | Expiry/supersession inventory partitions |
| Delivery execution disabled; admin replay/rotation remains reachable | Startup validation with missing required KEK | 0 | Startup fails before enable-flag return | `disabledExecutionStillFailsClosedWhenAdministrationNeedsUnavailableKey` |
| Delivery execution disabled; all required KEKs configured | Startup validation | 0 | Startup succeeds without requiring worker-only URL/timeout settings | Disabled-mode positive contract |
| Old replay HMAC version is required but missing | Startup validation | `< replay TTL` | Replay-key startup validator fails independently of webhook KEK validator | HMAC key inventory contracts |

## Mandatory closure questions

### What can change after this check and before use?

Nothing can serve requests during `@PostConstruct` validation. After startup,
new ciphertext is written only with the configured current KEK; the immutable
keyring remains available to the service instance. Time can expire old state,
which only reduces the required inventory. Operational key removal requires a
restart, where inventory runs again.

### What makes a new lifecycle epoch different from a replay?

KEKs encrypt stored secret material; they do not derive replay resources. New
ciphertext uses the configured current version, while replay decrypts the
persisted version named by the ciphertext envelope.

## Primary deterministic proof

- `disabledExecutionStillFailsClosedWhenAdministrationNeedsUnavailableKey`
- `requiredKeyInventoryIncludesCurrentPreviousAndReplayCiphertext`

Aggregate suite totals are regression context only.
