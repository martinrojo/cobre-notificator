#!/bin/sh
# Creates the notification flow topics and (re)applies their retention. Idempotent: safe to re-run.
# Partitions are never changed on existing topics: keys are event_id (no per-client ordering), but
# a repartition is an operational change, not a provisioning one. Start over with docker compose down -v.
set -eu

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
BIN=/opt/kafka/bin
# ponytail: RF 1 for the single local broker; production uses RF 3 with min.insync.replicas=2.
REPLICATION_FACTOR=1

DAY_MS=86400000

topic() {
  name="$1"
  partitions="$2"
  retention_ms="$3"
  "$BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$name" --partitions "$partitions" --replication-factor "$REPLICATION_FACTOR" \
    --config retention.ms="$retention_ms" --config cleanup.policy=delete
  "$BIN/kafka-configs.sh" --bootstrap-server "$BOOTSTRAP" --alter \
    --entity-type topics --entity-name "$name" \
    --add-config retention.ms="$retention_ms",cleanup.policy=delete
}

# The main topic carries all the traffic; retry, DLT and replay only what fails or is replayed.
topic transactions.events.v1             10 $((7 * DAY_MS))
topic notificator.retry.v1-0              3 $DAY_MS
topic notificator.retry.v1-1              3 $DAY_MS
topic notificator.retry.v1-2              3 $DAY_MS
topic notificator.dlt.v1                  3 $((14 * DAY_MS))
topic notifications.replay-requested.v1   3 $((7 * DAY_MS))

echo "Topics provisioned"
