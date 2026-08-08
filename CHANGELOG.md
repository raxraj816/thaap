# Changelog

Every released version of THAAP, what changed in it, and what it is and is not
licensed to do. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version an instance is running is readable over HTTP at `/actuator/info`,
which reports both the semantic version and the commit the artifact was built
from. Container images carry the same pair as `org.opencontainers.image.version`
and `org.opencontainers.image.revision`.

## What a THAAP version number means

**THAAP is a payment and fraud *simulator*. It processes no real card data, it
is not PCI-scoped, and it moves no real money.** `1.0.0` records that six phases
of payment, fraud, ML-lineage, tenancy and operational correctness are complete,
proven and deployable — not that the system is certified, licensed, or connected
to an acquirer. That boundary is stated in `docs/VISION.md` and `ADR-0002`, it is
unchanged by deploying the system, and it is restated here because a major
version is the single strongest signal a reader takes as "ready for production
traffic".

Real card acceptance is Phase 11 work behind a security and privacy review and an
approved design partner.

## [Unreleased]

Nothing yet.

## [1.0.0] — 2026-08-08

The first released version. It carries the whole of Phases 1–6 plus the build and
release work of Phase 6.5, and it is the first THAAP artifact that can be run by
someone who did not write it.

### Added

- **Release identity.** `/actuator/info` reports the artifact version and the
  commit it was built from. Both container images carry the same pair as OCI
  labels, and `scripts/release-identity-check.sh` builds an image, starts it, and
  fails unless the running instance and the image labels agree with the working
  tree.
- **A production runtime configuration contract.** Under the `aws` profile the
  application refuses to start when any of the eight security-relevant secrets is
  absent, empty, too weak, reused across two purposes, or still set to a value
  published in this repository for local development. Documented in full in
  `docs/references/RUNTIME_CONFIGURATION_REFERENCE.md`.
- **Reproducible container images** for `payments-api` and
  `analyst-dashboard-ui`, multi-stage and digest-pinned, running as non-root with
  no build toolchain and no credential in any layer.
- **`docker compose up` starts THAAP end to end** — API, workspace, PostgreSQL,
  Redis and Kafka — rather than three empty databases.
- **A changelog and a version.** `pom.xml` moves from `0.0.1-SNAPSHOT` to
  `1.0.0`; `v1.0.0` is the first tag in the repository's history.

### Changed

- Every floating `"latest"` dependency specifier in the analyst workspace is
  pinned to the exact version its lockfile already resolved, so a deploy-time
  install cannot resolve a tree nobody reviewed.
- `build-info` deliberately omits the build timestamp: it is the only recorded
  property that is not a function of the source, and including it would make one
  commit produce a different artifact on every rebuild.

### Fixed

- `PaymentRiskProjectionHealthIndicator` reported readiness `DOWN` on a healthy
  idle system, which behind a load balancer would have removed every task from
  rotation minutes after traffic went quiet. Readiness is now gated on pending
  work rather than elapsed time (`ER-0104`).
- Three defects in the payment-investigation demo, each hiding the next, found by
  running it against the containerised stack for the first time (`ER-0103`).

### Known limits

These are recorded as part of the release, not as a disclaimer appended to it.

- No real card data, no PCI scope, no real money movement.
- Kafka runs as a single broker. It is a single point of failure, replaced by MSK
  when a real merchant pilot begins or earlier if a broker restart is observed to
  lose an event the outbox cannot replay.
- RDS is single-AZ. An availability-zone failure is downtime plus a restore, not a
  failover.
- The deployment is private and invite-only. Opening the URL is a separate
  decision with its own promises.
- `THAAP_WEBHOOK_EXECUTION_ENABLED` defaults `false`. Enabling it makes
  `THAAP_WEBHOOK_ALLOWED_HOSTS` a live SSRF boundary that must be verified against
  the deployed network first.
