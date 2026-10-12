# Design

## Context

- El notificator consume `transactions.events.v1` con un `@RetryableTopic` sobre `notificator.retry.v1-0..2` y `notificator.dlt.v1`. El intento sale del tópico (`TopicAttempts`), y `attempts` es el fencing token de todas las escrituras (ver design de `notificator-idempotent-delivery`, decisiones 1, 2 y 5).
- El manager publica `{event_id, client_id, requested_at}` con key `event_id` en `notifications.replay-requested.v1`, y nadie lo consume. Su decisión de replay (202, 409, 404) depende de `delivery_status` y de si `last_replay_at` es nulo.
- El usuario de DB `notificator` tiene SELECT/INSERT/UPDATE sobre `notification_event`, y el `manager` solo SELECT. Ninguno tiene DDL: el esquema cambia en `notificator/db/01-schema.sql`.

Motivación: ver proposal.md. Comportamiento: ver `specs/notification-event-replay/spec.md` y `specs/notification-delivery/spec.md`.

## Goals / Non-Goals

**Goals:**
- `(generation, attempts)` como fencing token: ningún mensaje de una generación anterior puede reclamar ni escribir sobre la generación actual.
- Mantener el mapeo tópico→intento y un solo consumer por tópico de retry.
- Sin comparaciones de fechas en ningún `WHERE`.

**Non-Goals:**
- Que el manager use `generation` para decidir. Solo la lee y la reenvía.
- Cambiar el calendario de reintentos o el tope de 4 intentos.

## Decisions

### 1. `generation INT NOT NULL DEFAULT 0` y condición en todo `UPDATE`

```sql
-- claim del replay (único UPDATE que cambia generation)
UPDATE notification_event
   SET delivery_status = 'PENDING', generation = generation + 1, attempts = 1,
       last_replay_at = :requestedAt
 WHERE event_id = :id AND client_id = :clientId
   AND delivery_status = 'FAILED' AND generation = :generation

-- el resto de los UPDATE suma la condición
... WHERE event_id = :id AND generation = :generation AND <condición actual>
```

Aplica a `claimAttempt`, `markDelivered`, `recordAttempt`, `refreshIfStale` y `failIfStale`. `insertClaim` es un `INSERT` y toma el default 0. `last_replay_at` se escribe pero nunca se compara: es informativo y sostiene la decisión "replay en curso" del manager.

`attempts = 1` en el claim: el replay ejecuta el intento 1 de la generación nueva. Con eso el job de recuperación y el DLT funcionan sin cambios de lógica, solo con la generación como parámetro extra.

`markDelivered` también lleva la condición. Un 2xx tardío de una generación anterior ya no cierra la generación nueva; ese POST repetido es parte del at-least-once (el cliente deduplica por `X-Event-Id`).

`client_id = :clientId` en el claim impide que un mensaje con un `client_id` alterado reabra un evento ajeno (OWASP A01). La entrega sigue usando los datos de la fila.

Alternativas descartadas:
- `last_replay_at` como generación. La comparación de `DATETIME(3)` contra el `Instant` serializado por el manager no es confiable (precisión y zona).
- Seguir numerando `attempts` (5, 6, ...). Rompe el mapeo tópico→intento y el tope por tópico.
- Que el notificator lea la generación de la fila en vez de recibirla. No distingue un pedido duplicado de uno nuevo: los dos verían `FAILED` y reabrirían dos veces.

### 2. La generación de un mensaje sale de su valor

| Valor | Generación |
|---|---|
| Formato `transactions.events.v1` (sin `generation`) | 0 |
| Pedido de replay con `generation = g` | g + 1 |

Los mensajes de retry y DLT conservan el valor de su origen, así que la regla vale en los cinco tópicos. El parser distingue los formatos por la presencia de `generation`. El contrato de la plataforma no tiene ese campo, y el manager siempre lo manda. Para retry y DLT alcanza con `event_id`, `client_id` y la generación; el resto sale de la fila.

### 3. Un solo listener para los dos tópicos principales

`@KafkaListener(topics = {transactions.events.v1, notifications.replay-requested.v1})` con el mismo `@RetryableTopic`. El handler despacha por `record.topic()`:
- principal → `first(event)` (sin cambios);
- replay → `replay(request)`: claim del replay y, si gana, `deliver(..., attempt 1)`;
- retry → `retry(eventId, generation, attempt)`.

`TopicAttempts.attemptFor` mapea el tópico de replay al intento 1.

Antes de esto va un spike: el names provider de `KafkaRetryConfig` devuelve el mismo nombre de retry para los dos tópicos principales, y hay que confirmar que spring-kafka 4.1.1 crea un solo endpoint por tópico de retry y no lo suscribe dos veces.

Resultado del spike (sources de spring-kafka 4.1.1): `RetryTopicConfigurer.processAndRegisterEndpoint` registra un endpoint por destino (retry-0..2 y DLT), con un container y un group cada uno. Sus tópicos son los principales mapeados por el names provider, es decir `[retry.v1-n, retry.v1-n]`, y el consumer de Kafka los deduplica en `subscribe`. `DefaultDestinationTopicResolver.addDestinationTopics` hace `putAll` por nombre de tópico: las entradas de retry y DLT de las dos cadenas son idénticas, y cada principal tiene la suya. Plan A viable; el test de integración de 8.1 lo confirma de punta a punta.

Plan B, si el spike falla: un `@KafkaListener` aparte para el replay, sin `@RetryableTopic`. Ante `RetryableDeliveryException` publica en `notificator.retry.v1-0` con `RetryPublisher` (igual que el job), y usa un error handler con el mismo backoff bloqueante para errores transitorios de DB. Si esa publicación falla después del claim, el job de recuperación la reanuda.

Alternativa descartada: dos `@RetryableTopic`. Cada mensaje de retry se procesaría dos veces, en groups distintos.

### 4. Recuperación y DLT con generación

- `findStale` devuelve `generation` y `last_replay_at`.
- Con `generation = 0`, el job republica como hoy: key `client_id` y valor de `transactions.events.v1`.
- Con `generation > 0`, republica un pedido de replay: key `event_id`, `generation - 1`, `client_id` de la fila y `requested_at = last_replay_at`. El consumer de retry deriva `generation` otra vez y reclama sobre la generación de la fila.
- `failExhausted(eventId, clientId, generation, attempt)` lleva la generación del mensaje.

### 5. Manager: lee y reenvía `generation`

`generation` se suma al `SELECT` compartido, a `NotificationEvent` y a `ReplayRequested(eventId, clientId, generation, requestedAt)`. La decisión de replay no cambia. El `JsonMapper` en `SNAKE_CASE` serializa el campo como `generation`.

### 6. Métricas y logs

- Counter `notificator.replays` con tag `outcome`: `started` o `discarded`.
- INFO `Replay started`, DEBUG `Replay discarded`, con `event_id`, `client_id` y `generation`.
- Los descartes de retry por generación caen en `notificator.discarded{reason="stale"}`, como cualquier intento obsoleto.

## Risks / Trade-offs

- [Un pedido de replay del manager queda obsoleto si la generación cambió entre su lectura y el consumo] → Es el comportamiento buscado. El manager solo publica sobre `FAILED`, y un pedido viejo no puede reabrir otra vez.
- [`attempts` deja de ser monótono en toda la vida del evento] → Es monótono dentro de cada generación, y `generation` nunca baja. La regla "attempts nunca baja" de `openspec/config.yaml` se reescribe en esos términos.
- [Mensaje de replay con `client_id` alterado (A01)] → El claim exige que el `client_id` coincida con el de la fila. Si no coincide, el pedido se descarta y no se entrega nada.
- [Spam de replays (A04)] → Cada pedido duplicado cuesta un `UPDATE` que no afecta filas. El rate limiting sigue en el gateway.
- [El spike del listener multi-tópico falla] → Plan B de la decisión 3, cubierto por el job de recuperación.
- [Contrato del tópico de replay roto para mensajes viejos sin `generation`] → Van al DLT como inválidos. En el entorno local no hay mensajes previos con consumidores.

## Migration Plan

Entorno local, sin datos productivos:
1. `docker compose down -v`, para recrear MariaDB con la columna y los tópicos vacíos.
2. `docker compose up --build`.

Rollback: volver a las imágenes anteriores. La columna nueva es inocua para el código anterior.
