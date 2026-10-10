# Proposal

## Why

El caso pide que un cliente pueda pedir el reenvío de una notificación que falló (Task 2, replay). Hoy el manager solo consulta eventos y no tiene forma de disparar una nueva entrega. El manager no escribe en la DB, así que el pedido tiene que viajar por Kafka al notificator, que es quien entrega y cambia estados.

## What Changes

- Nuevo endpoint `POST /notification_events/{notification_event_id}/replay` en el manager:
  - Busca el evento con una sola consulta por `event_id` y `client_id`. Si no existe o es de otro cliente, responde `404` con la misma respuesta en ambos casos.
  - `FAILED` → publica un `ReplayRequested` y responde `202` solo cuando Kafka confirma la escritura (ack).
  - `PENDING` o `RETRYING` con `last_replay_at` no nulo (hay un replay en curso) → `202` sin publicar.
  - Cualquier otro caso → `409`.
  - Si la publicación falla o vence el timeout → `503` con `Retry-After`.
  - Todos los errores en formato Problem Details (RFC 9457).
- Mensaje `ReplayRequested` (`event_id`, `client_id`, `requested_at` en UTC) en el tópico `notifications.replay-requested.v1`, con key `client_id`.
- Productor Kafka en el manager con `acks=all`, idempotencia habilitada y `max.block.ms` bajo y configurable.
- **BREAKING (infra local)**: el tópico `notifications.replay-requested.v1` reemplaza a `notificator.replay.v1`. Misma retención (7 días) y particiones (3).
- Nueva columna `last_replay_at DATETIME(3) NULL` en `notification_event`, agregada en `notificator/db/01-schema.sql`. Requiere recrear el volumen de MariaDB.

La idempotencia del efecto es responsabilidad del notificator. Ante pedidos concurrentes, el manager puede publicar más de una vez; eso es aceptable.

Aplicaciones afectadas: **manager** (endpoint, productor Kafka). La infraestructura cambia en `kafka/create-topics.sh`, `notificator/db/01-schema.sql` y `docker-compose.yml`. No se toca código del **notificator** ni del **gateway**.

### Fuera de alcance

- Consumo de `notifications.replay-requested.v1` en el notificator, la transición condicional `FAILED → PENDING` y la escritura de `last_replay_at`. Va en el cambio que implemente el notificator.
- Rate limiting de replays por cliente. Corresponde al gateway.
- Exponer `last_replay_at` en las respuestas de `GET /notification_events`.
- Deduplicación de publicaciones en el manager (outbox, locks, claves de idempotencia).
- Migración de bases existentes: no hay herramienta de migraciones, se recrea el volumen.

## Capabilities

### New Capabilities
- `notification-event-replay`: pedido de replay de un evento del cliente: reglas por estado, aislamiento por cliente, publicación confirmada en Kafka y errores Problem Details.

### Modified Capabilities
- `event-topics`: el tópico de replay pasa de `notificator.replay.v1` a `notifications.replay-requested.v1`. Deja de ser interno del notificator y pasa a ser el contrato entre manager y notificator.

## Impact

- `manager/`:
  - nuevo use case de replay con su servicio;
  - port out de publicación y adapter Kafka;
  - `last_replay_at` en el modelo y en el SELECT del repositorio JDBC;
  - handlers 409 y 503 en `ApiExceptionHandler`;
  - nuevo método en `NotificationEventController`.
- Dependencias del manager: `spring-boot-starter-kafka` y `spring-boot-starter-actuator` + `micrometer-registry-prometheus`, todas dentro del stack definido.
- `docker-compose.yml`: el manager recibe el bootstrap de Kafka y espera a que `kafka-init` termine bien.
- `kafka/create-topics.sh`, `README.md`, `openspec/config.yaml`: nombre nuevo del tópico de replay.
- `notificator/db/01-schema.sql`: columna `last_replay_at`. El usuario `manager` ya tiene SELECT sobre la tabla.
