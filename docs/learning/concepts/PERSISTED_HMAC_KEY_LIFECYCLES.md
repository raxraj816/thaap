# Persisted HMAC Key Lifecycles

## Mental model

An HMAC output can be a privacy pseudonym, a lookup identity, a request
commitment, or a derived credential input. Those are different protocols even
when they use the same primitive. Rotation is safe only when the storage and
lookup model preserves the promise made by that specific protocol.

## Replay identities

Replay state is bounded. THAAP retains it for 24 hours.

- A request commitment stores the key version that produced it and verifies
  replay with that original version.
- A hashed external lookup key needs current/previous aliases that all point to
  one durable record.
- Startup fails if a non-expired record requires a missing key.
- Once the replay window expires, the record can restart or retire without the
  historical key.

Merchant creation needs both mechanisms because the external key is hashed and
the API key is deterministically derived. Its derivation also includes the
immutable creation-record UUID: live replay in one record returns the same API
key, while reusing an expired external key begins a new lifecycle epoch and
cannot collide with the old merchant credential even if the request and pepper
are unchanged. Generic idempotency stores the
external key directly, so only its request commitment needs a version. Webhook
creation hashes the external key, so it needs tenant-scoped aliases plus a
versioned request commitment.

## Correlation identities

Card fingerprints and customer pseudonyms are long-lived analytical identities.
Changing their key changes the identifier and splits velocity, graph, and model
lineage. They therefore use a separately managed stable correlation key.
Changing that key is an explicit offline data re-key with dual-identity
reconciliation, not part of routine online replay-key rotation.

## Rolling deployment

1. Deploy keyring-capable code everywhere while the old replay key remains
   current and both old/new keys are configured.
2. Promote the new replay key only after every instance understands aliases and
   persisted versions.
3. Retain the old key through every non-expired replay window.
4. Remove it only when startup inventory no longer requires it.

Key-version names are immutable. Reusing a name for different bytes corrupts
the protocol; renaming unchanged bytes creates unnecessary historical-key
retention. THAAP rejects duplicate key material under different version labels
so identity aliases and key-retirement checks cannot silently disagree.

## Review checklist

- Inventory every persisted HMAC output and downstream consumer.
- Identify whether lookup, comparison, derivation, or correlation uses it.
- Prove old/current mixed writers against the production database.
- Prove same request, changed request, missing old key, exact expiry, and
  post-expiry retirement.
- For derived credentials, prove live replay and expired same-pepper reuse as
  separate lifecycle epochs.
- Reject duplicate key material under different version labels.
- Keep fraud/ML correlation outside routine replay-key rotation.
