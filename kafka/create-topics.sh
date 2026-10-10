#!/bin/sh
# Creates the notification flow topics and (re)applies their retention. Idempotent: safe to re-run.
# Partitions are never changed on existing topics: adding them would remap client_id keys and break per-client ordering.
set -eu

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
BIN=/opt/kafka/bin
PARTITIONS=3
# ponytail: RF 1 for the single local broker; production uses RF 3 with min.insync.replicas=2.
REPLICATION_FACTOR=1

DAY_MS=86400000

topic() {
  name="$1"
  retention_ms="$2"
  "$BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$name" --partitions "$PARTITIONS" --replication-factor "$REPLICATION_FACTOR" \
    --config retention.ms="$retention_ms" --config cleanup.policy=delete
  "$BIN/kafka-configs.sh" --bootstrap-server "$BOOTSTRAP" --alter \
    --entity-type topics --entity-name "$name" \
    --add-config retention.ms="$retention_ms",cleanup.policy=delete
}

topic transactions.events.v1             $((7 * DAY_MS))
topic notificator.retry.v1-0             $DAY_MS
topic notificator.retry.v1-1             $DAY_MS
topic notificator.retry.v1-2             $DAY_MS
topic notificator.dlt.v1                 $((14 * DAY_MS))
topic notifications.replay-requested.v1  $((7 * DAY_MS))

echo "Topics provisioned"
