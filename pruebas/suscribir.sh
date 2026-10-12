#!/bin/sh
# Creates or updates the subscriptions of a client so the notificator delivers its events to a webhook.
# Usage: pruebas/suscribir.sh <client_id> <webhook_url> [event_type ...]
# Without event types it subscribes all of them. Idempotent: re-running replaces the URL and reactivates.
set -eu

ALL_TYPES="credit_card_payment credit_cashback credit_deposit credit_refund credit_transfer
debit_automatic_payment debit_card_withdrawal debit_purchase debit_subscription"

if [ $# -lt 2 ]; then
  echo "Uso: $0 <client_id> <webhook_url> [event_type ...]" >&2
  echo "Ejemplo: $0 CLIENT100 https://webhook.site/abc credit_transfer" >&2
  exit 1
fi

client_id="$1"
url="$2"
shift 2
types="${*:-$ALL_TYPES}"

# Values go into SQL: accept only characters that cannot break out of a quoted literal.
echo "$client_id" | grep -Eq '^[A-Za-z0-9_-]{1,64}$' || { echo "client_id inválido: $client_id" >&2; exit 1; }
echo "$url" | grep -Eq "^https?://[^'\"\\\\[:space:]]{1,2040}$" || { echo "URL inválida: $url" >&2; exit 1; }
case "$url" in
  http://*) echo "Aviso: el notificator solo acepta http con WEBHOOK_ALLOW_HTTP=true (activo en docker-compose.yml)." >&2 ;;
esac

values=""
for type in $types; do
  echo "$type" | grep -Eq '^[a-z_]{1,64}$' || { echo "event_type inválido: $type" >&2; exit 1; }
  values="$values${values:+, }('$client_id', '$type', '$url')"
done

cd "$(dirname "$0")/.."
docker compose exec -T mariadb sh -c 'mariadb -u root -p"$MARIADB_ROOT_PASSWORD" "$MARIADB_DATABASE"' <<SQL
INSERT INTO subscription (client_id, event_type, webhook_url)
VALUES $values
ON DUPLICATE KEY UPDATE webhook_url = VALUES(webhook_url), active = TRUE;
SELECT client_id, event_type, webhook_url, active FROM subscription WHERE client_id = '$client_id';
SQL
