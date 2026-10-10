#!/bin/bash
# Creates one DB user per application with least-privilege grants.
# Sourced by the MariaDB entrypoint (keep it NON-executable so docker_process_sql is available).
# Passwords come from the environment, never from the repo.
set -euo pipefail

: "${MARIADB_DATABASE:?MARIADB_DATABASE is required}"
: "${NOTIFICATOR_DB_USER:?NOTIFICATOR_DB_USER is required}"
: "${NOTIFICATOR_DB_PASSWORD:?NOTIFICATOR_DB_PASSWORD is required}"
: "${MANAGER_DB_USER:?MANAGER_DB_USER is required}"
: "${MANAGER_DB_PASSWORD:?MANAGER_DB_PASSWORD is required}"

docker_process_sql --database=mysql <<-EOSQL
    CREATE USER '${NOTIFICATOR_DB_USER}'@'%' IDENTIFIED BY '${NOTIFICATOR_DB_PASSWORD}';
    GRANT SELECT ON \`${MARIADB_DATABASE}\`.subscription TO '${NOTIFICATOR_DB_USER}'@'%';
    GRANT SELECT, INSERT, UPDATE ON \`${MARIADB_DATABASE}\`.notification_event TO '${NOTIFICATOR_DB_USER}'@'%';

    CREATE USER '${MANAGER_DB_USER}'@'%' IDENTIFIED BY '${MANAGER_DB_PASSWORD}';
    GRANT SELECT ON \`${MARIADB_DATABASE}\`.notification_event TO '${MANAGER_DB_USER}'@'%';

    FLUSH PRIVILEGES;
EOSQL
