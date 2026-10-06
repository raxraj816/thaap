# THAAP AWS Topology

Broker memory boundary: the pinned image defaults to a 1 GiB heap, which cannot fit safely
inside a 1 GiB task. Both ECS and compose explicitly use `-Xms512m -Xmx512m`; compose's
1 GiB memory ceiling and real container-replacement proof exercise that floor. This is
local startup/durability proof, not production-load sizing or AWS EFS recovery evidence.

The Phase 6.5 Section 6 topology, defined so it is rebuildable from an empty account.

Nothing load-bearing may be created by hand in the console. The account itself, IAM Identity
Center, the domain registration, the Route53 hosted zone and the AWS Budget are the deliberate
exceptions — they are the substrate this builds on. Everything else is here, because a resource
clicked into existence is invisible to `terraform destroy` and to the next rebuild, and the frozen
promise for this chapter is that the whole topology comes back from version control.

## Status

**Authored and validated. Never applied.** `terraform apply` requires an AWS account, which does
not exist yet, so `C63-SEC-001`, `C63-OPS-001` and `C63-API-001` are `PENDING` in
`docs/reviews/PHASE_6_5_INVARIANT_PROOF_MATRIX.md` and Chapter 63 stays open. What has run is
`fmt`, `init -backend=false` and `validate` on both roots, plus the two structural gates below.
A configuration that parses is not a topology that works. The 2026-10-06 repair audit adds
mocked Terraform plans and computed ACM/DNS resolution, executable edge routing/header
partitions, real isolated Docker startup, and broker-container replacement evidence. None
substitutes for the still-unexecuted live AWS exposure, deployment or recovery proofs.

## Running it

Terraform is not installed on any developer machine. `scripts/terraform.sh` runs a pinned version
in a container, so a laptop and CI cannot disagree about what the configuration means:

```bash
scripts/terraform.sh validate
```

## Order of operations

The state backend cannot store its own creation, so there are two roots:

```text
bootstrap/   S3 state bucket + DynamoDB lock table, local state, applied once
  |
  '-> prints the -backend-config for the main root
        |
        '-> main root, remote state, everything else
```

1. Apply `bootstrap/`, and keep the `backend_config` output it prints.
2. `init` the main root with that backend configuration.
3. Supply the main root inputs, including an immutable API image reference. Apply the foundation
   with `api_desired_count=0` (the safe default). This creates ECR, all secret **entries** and the
   topology without launching an API task. Never use routine `-target` applies to stage this.
4. Populate the eight runtime secret entries using the names in `runtime_secret_ids`. The
   application refuses to start without them. Do not put values in `.tfvars`, Terraform output,
   shell history or a CI log; use the approved Secrets Manager write procedure with restricted files.
5. Publish and verify the ARM64 API image in the immutable ECR repository. Build and upload the
   SPA bundle to the private `spa_bucket` (Chapter 64), serving `index.html` with revalidation
   and hashed assets as immutable. A topology with an empty bucket is not a working workspace.
6. Set `api_desired_count=2` in the deployment configuration and plan/apply. Capture the real
   readiness, migration, identity, routing and exposure evidence before declaring it deployed.

These steps require owner-confirmed AWS/account/domain prerequisites. They are instructions,
not authorization to create paid resources. A second-region instance needs a distinct state key,
environment/resource namespace and workspace hostname; a variable-only mock plan is not a
multi-region deployment. The workspace bucket includes the region to avoid a global-name collision.

## Secrets

Terraform creates the Secrets Manager entries and never their values. A
`aws_secretsmanager_secret_version` carrying real key material would write it into the state file
and into every plan output, and this repository already refuses to let a secret reach an image
layer; a state file is the same mistake in a different medium.

The operator writes each once. Five need `openssl rand -hex 32`, three need
`openssl rand -base64 32`, all eight must differ from one another, and none may be a value
published anywhere in this repository — `RuntimeSecretContractGate` refuses all three cases at
startup. `docs/references/RUNTIME_CONFIGURATION_REFERENCE.md` says which is which.

The two Terraform does populate are the RDS master password and the CloudFront-to-ALB shared
header, because no human ever needs to know either.

## Exposure — Section 5.1

The authored deployment is designed to be private by mechanism in three layers. Until live
outside-allowlist and origin-bypass probes pass, this is a design, not a certified private deployment.

| Layer | Where | What it stops |
|---|---|---|
| WAF WebACL, default `BLOCK`, operator IP set | `waf.tf`, on the distribution | Anyone who finds the hostname — and Certificate Transparency publishes it within minutes of certificate issuance |
| Shared secret header, CloudFront to ALB | `alb.tf` listener rule, `cloudfront.tf` origin | The ALB's own `*.elb.amazonaws.com` name being a way around the edge entirely |
| Security groups by group reference | `network.tf` | A task in a public subnet — there is no NAT Gateway — being addressable directly |

The middle layer prevents direct-origin bypass but does not sanitize headers from an allowed
viewer. `edge-request.js.tftpl` also removes forged `Forwarded`/`X-Forwarded-*` metadata and
rebuilds the client address from CloudFront's viewer socket before the HTTPS ALB hop. The
forwarded read-only Host is intentionally retained so the ALB certificate matches the workspace
hostname. WAF rate blocking runs **before** its terminating allow rule. The IPv4 operator set
rejects malformed/IPv6 and any `/0` representation; IPv6 requests remain blocked by default.

## Gates

| Gate | Holds |
|---|---|
| `scripts/terraform-region-parameterisation-check.py` | No literal region outside `variables.tf`, so a second region is configuration rather than a rewrite |
| `scripts/routing-contract-check.py` | All 16 published API roots and four operational roots agree across routers and smoke proof; the OpenAPI inventory catches shared omissions |
| CI `Terraform validate` | `fmt`, `init` and `validate` on both roots, mocked plan/computed-DNS tests and executable edge request tests |

Local commands: `scripts/terraform.sh test -no-color`,
`node --test scripts/edge-request.test.mjs`, `bash scripts/broker-persistence-check.sh`, and
`scripts/compose-stack-check.sh`. The stack proof refuses an occupied namespace before installing
cleanup, uses project-scoped containers/volumes, checks API JSON errors rather than non-502 status,
and asserts all V1–V91 SQL **and Java** migrations. Dirty builds report a `-dirty` commit marker;
the separate clean-tree release gate still refuses them.

SPA navigations are rewritten before S3 lookup; API 403/404 and missing asset responses are never
mapped globally to HTML 200. The shell is uncached at CloudFront; hashed `/assets/*` use the
optimized policy. Application target 5xx and ALB-generated 5xx have separate alarm configurations.
Projection lag/restart signals and observed notification delivery remain Chapter 65 work.

Both scripts run in `scripts/commit-readiness-check.sh` and were proven against deliberately
broken input — a smuggled region literal, a prefix added to the dev proxy and forgotten here, and
a quietly changed behaviour-generation rule — before being trusted.

## Recorded tradeoffs

These are decisions, not omissions. Section 13 of the phase plan owns them.

- **Single-AZ RDS.** An availability-zone failure is downtime plus a restore, not a failover.
- **One Kafka broker.** A single point of failure, replaced by MSK when a real merchant pilot
  begins or earlier if a broker restart is observed to lose an event the outbox cannot replay.
- **No NAT Gateway.** Tasks run in public subnets; the security groups are what make that safe.
- **Two subnets across two zones.** The minimum an ALB accepts. It buys load-balancer validity,
  not database durability.
