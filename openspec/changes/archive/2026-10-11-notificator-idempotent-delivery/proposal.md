# Proposal

## Why

El notificator todavía no consume nada: solo existe la clase de arranque. El caso pide entregar cada evento de `transactions.events.v1` al webhook de su cliente, con reintentos y con el resultado final persistido. Kafka entrega at-least-once y los eventos pueden llegar duplicados. Además, el principal, los retries, un rebalance o un crash pueden hacer que dos hilos trabajen sobre el mismo evento. Sin un diseño explícito de idempotencia, eso genera cadenas de retry en paralelo, POSTs de más y eventos que quedan trabados en `PENDING` o `RETRYING` para siempre.

## What Changes

- Consumo de `transactions.events.v1` en el notificator con `@RetryableTopic`, sobre los tópicos existentes `notificator.retry.v1-0..2` y `notificator.dlt.v1`.
- **Claim antes de cada POST**:
  - El primer intento reclama el evento con un `INSERT` sobre la PK `event_id`. Si la fila ya existía, el mensaje es un duplicado y se descarta sin entregar.
  - Cada reintento reclama con un `UPDATE` condicional sobre `attempts`.
  - Solo quien gana el claim llama al webhook.
- **`attempts` pasa a contar intentos iniciados** y nunca disminuye. Es el fencing token de todas las escrituras de estado. Un mensaje viejo o duplicado pierde el CAS y se descarta sin POST.
- Validación de suscripción vigente (`client_id`, `event_type`) en cada intento, entrega HTTPS síncrona al webhook y clasificación del resultado:
  - 2xx → `DELIVERED`;
  - timeout, error de conexión, 408, 429 o 5xx → reintentable;
  - otro código → `FAILED`;
  - sin suscripción → `NOT_SUBSCRIBED`.
- Reintentos con backoff de 60 s, 300 s y 1500 s. Al agotarse, el handler del DLT marca `FAILED`.
- **Reintentos bloqueantes ante errores transitorios de la DB**: el mensaje se reintenta en el mismo offset y no pasa a los tópicos de retry. Así un evento nunca llega a un retry ni al DLT sin su fila.
- **Job de recuperación**: detecta filas en `PENDING` o `RETRYING` que dejaron de avanzar (crash, rebalance, publicación perdida) y las reanuda:
  - publica en el `notificator.retry.v1-<n>` que corresponde, sin modificar `attempts`;
  - si ya no quedan intentos, las marca `FAILED`.
- Métricas y logs de entregas, duplicados descartados, DLT, latencia del webhook y recuperaciones.
- Nuevo índice `(delivery_status, updated_at)` en `notification_event` para la consulta del job.

Aplicaciones afectadas: **notificator**. La infraestructura cambia en `notificator/db/01-schema.sql` (índice). No se toca el **manager** ni el **gateway**.

### Fuera de alcance

- Consumo de `notifications.replay-requested.v1` y la transición `FAILED → PENDING` del replay. Ese cambio tiene que mantener `attempts` monótono: hoy el diseño del replay lo vuelve a 0, y eso reabre el ABA analizado en el design.
- Circuit breaker por cliente con Resilience4j. Va en un cambio aparte, sobre la misma clasificación de errores reintentables.
- Firma de la petición al webhook (HMAC) y autenticación del cliente receptor.
- Alta y administración de suscripciones (el notificator solo lee `subscription`), y un mock de webhook para el entorno local.
- Garantizar un único POST por evento: la entrega HTTP es at-least-once, y `X-Event-Id` le permite al cliente deduplicar.
- Dashboard de Grafana. Las métricas quedan expuestas en `/actuator/prometheus`.

## Capabilities

### New Capabilities
- `notification-delivery`: entrega de los eventos de `transactions.events.v1` al webhook del cliente suscrito, con su ciclo de estados, la idempotencia por `event_id` ante duplicados y concurrencia, los reintentos, el DLT y la recuperación de entregas trabadas.

### Modified Capabilities
- `event-topics`: el requisito de key de los mensajes de retry suma el caso de los mensajes que publica el job de recuperación. Esos mensajes no tienen un mensaje de origen en Kafka y se reconstruyen desde la fila, con la misma key `client_id`.

## Impact

- `notificator/`:
  - dominio, servicios de aplicación y ports de entrega y recuperación;
  - listener Kafka con `@RetryableTopic`, handler del DLT y publisher de retry para el job;
  - repositorios JDBC de `notification_event` y `subscription`;
  - cliente HTTP del webhook;
  - tarea programada;
  - configuración de consumer y producer, métricas y logs.
- Dependencias: ninguna nueva. Se usan `spring-boot-starter-kafka`, `-jdbc`, `-webmvc` (`RestClient`) y `-actuator` con Micrometer, que ya están en el `pom.xml`.
- `notificator/db/01-schema.sql`: nuevo índice. Requiere recrear el volumen de MariaDB.
- `docker-compose.yml`: variables de configuración del webhook para el notificator.
- `docs/flujo-notificator.mmd`, `README.md` y `openspec/config.yaml` se actualizan con el flujo nuevo.
