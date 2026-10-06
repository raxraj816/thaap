#!/usr/bin/env bash
set -euo pipefail

docker exec \
  -e PGPASSWORD="${THAAP_OLD_POSTGRES_PASSWORD:-thaap_local_password}" \
  "${THAAP_POSTGRES_CONTAINER:-thaap-local-postgres-1}" \
  psql -U "${THAAP_POSTGRES_USER:-thaap}" -d "${THAAP_POSTGRES_DB:-thaap_payments}" \
  -c "ALTER USER ${THAAP_POSTGRES_USER:-thaap} WITH PASSWORD '${THAAP_POSTGRES_PASSWORD:-thaap}';"
