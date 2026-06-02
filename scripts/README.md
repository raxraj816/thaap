# Scripts

Developer utilities for repeatable local workflows.

## Local Infrastructure

Start PostgreSQL and Redis:

```bash
scripts/local-infra-up.sh
```

Check container status:

```bash
scripts/local-infra-ps.sh
```

Stop local infrastructure:

```bash
scripts/local-infra-down.sh
```

Reset the local PostgreSQL password to the documented default:

```bash
scripts/local-postgres-reset-password.sh
```

## payments-api

Run the Spring Boot API from the repository root:

```bash
scripts/payments-api-run.sh
```

## Learning Workflow

Check that today's Staff apprenticeship learning artifacts exist:

```bash
scripts/learning-day-check.sh
```

For non-trivial implementation days:

```bash
scripts/learning-day-check.sh --deep-dive-required
```

Check that the weekly architecture review and growth-tracking artifacts exist:

```bash
scripts/learning-week-check.sh
```
