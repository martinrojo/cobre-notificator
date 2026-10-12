# Proposal

## Why

El manager ya publica pedidos de replay en `notifications.replay-requested.v1`, pero nadie los consume: un cliente recibe 202 y su evento sigue en `FAILED` para siempre. Reabrir un evento exige volver a numerar sus intentos desde 1 (el intento sale del tópico), y hoy `attempts` es el único fencing token: un mensaje viejo de un retry podría reclamar un intento de la entrega nueva (ABA). Hace falta un token de generación que no dependa de comparar fechas, porque la serialización de un `DATETIME(3)` de MariaDB contra un `Instant` del manager no es confiable.

## What Changes

- **Columna `generation INT NOT NULL DEFAULT 0`** en `notification_event`. Cada replay aceptado la incrementa en 1. Todo `UPDATE` del notificator sobre `notification_event` lleva `generation = :generation` en el `WHERE`, junto con las condiciones que ya tiene.
- **El manager lee `generation`** y la incluye en el pedido de replay: el valor pasa a ser `event_id`, `client_id`, `generation` y `requested_at`. **BREAKING** para el contrato de `notifications.replay-requested.v1`; hoy no tiene consumidores.
- **Consumo de `notifications.replay-requested.v1` en el notificator.** El replay se reclama con un único `UPDATE` condicional: `FAILED → PENDING`, `generation = generation + 1`, `attempts = 1`, `last_replay_at = requested_at`, solo si el evento sigue en `FAILED`, con ese `client_id` y con la `generation` del mensaje. Si afecta 0 filas, el pedido es un duplicado, está obsoleto o no corresponde al dueño, y se descarta sin POST. Si gana, se entrega como intento 1 de la nueva generación.
- **Un solo set de retry/DLT para los dos flujos.** El replay usa `notificator.retry.v1-0..2` y `notificator.dlt.v1` con el mismo mapeo tópico→intento y un único consumer por tópico de retry. La generación de un mensaje sale de su valor: un mensaje de `transactions.events.v1` es la generación 0, y un pedido de replay con `generation = g` abre y lleva la generación `g + 1`.
- **`attempts` vuelve a 1 en cada replay.** Deja de ser monótono en toda la vida del evento y pasa a serlo dentro de cada generación. El par `(generation, attempts)` es el fencing token.
- **El job de recuperación** reanuda también las generaciones de replay: republica con el formato y la key del flujo que corresponde a la fila (key `event_id` y valor de replay cuando `generation > 0`).
- Métricas y logs de replays tomados y descartados.

Aplicaciones afectadas: **notificator** (consumo, repositorio, recuperación, esquema) y **manager** (lectura de `generation` y contenido del mensaje). No se toca el **gateway**.

### Fuera de alcance

- Cambios en las reglas del endpoint del manager (202, 409, 404, 503): la decisión de replay sigue siendo la misma.
- Límite de replays por evento o por cliente. El rate limiting queda en el gateway.
- Historial de generaciones o de intentos anteriores: la fila guarda solo el último intento.
- Migración de datos existentes: el entorno es local y se recrea con `docker compose down -v`.

### Dependencia

`notificator-idempotent-delivery` tiene que archivarse antes que este cambio: los requisitos de `notification-delivery` que se modifican acá viven todavía en ese cambio.

## Capabilities

### New Capabilities
<!-- ninguna -->

### Modified Capabilities
- `notification-event-replay`: el mensaje de pedido de replay suma `generation`, y se agregan los requisitos del consumo del pedido en el notificator (claim del replay, descarte de duplicados y obsoletos, entrega y reintentos de la nueva generación).
- `notification-delivery`: los claims y escrituras de estado pasan a estar condicionados también por `generation`; `attempts` es monótono dentro de cada generación; la recuperación y el DLT respetan la generación del mensaje.

## Impact

- `notificator/db/01-schema.sql`: columna `generation`.
- notificator: `TopicAttempts`, `TransactionEventListener` (o el listener que consuma los dos tópicos principales), `DeliveryService`, `ExhaustedEventService`, `StaleDeliveryRecoveryService`, `NotificationEventRepository` y `JdbcNotificationEventRepository`, `RetryPublisher` y `KafkaRetryPublisher`, modelos del dominio, tests de integración del flujo.
- manager: `NotificationEvent`, `ReplayRequested`, la consulta del repositorio JDBC, `NotificationEventReplayService` y sus tests.
- `openspec/config.yaml`: la regla "attempts nunca baja" pasa a valer por generación.
