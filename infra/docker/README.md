# Docker Infrastructure

Local Docker Compose starts here on Day 2. The canonical compose file is
`infra/docker/compose.yml`.

## Initial Services

- PostgreSQL
- Redis

## Commands

From the repository root:

```bash
scripts/local-infra-up.sh
scripts/local-infra-ps.sh
scripts/local-infra-down.sh
```

Or call Docker Compose directly:

```bash
docker compose -f infra/docker/compose.yml up -d --wait postgres redis
docker compose -f infra/docker/compose.yml ps
docker compose -f infra/docker/compose.yml down
```

Default local settings are documented in `.env.example`; override them by
exporting environment variables or creating a local `.env` file in this
directory.

## pgAdmin Connection

Use these defaults when registering the local PostgreSQL server in pgAdmin:

```text
Host: localhost
Port: 5432
Maintenance database: thaap_payments
Username: thaap
Password: thaap
```

If the Postgres volume was initialized before these defaults were aligned, run
`scripts/local-postgres-reset-password.sh` once while the container is running.

## Later Local Services

- Kafka
- ClickHouse
- Neo4j
- object storage simulator
- MLflow
- Prometheus
- Grafana
