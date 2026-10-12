# Proposal

## Why

Hoy la PK de `notification_event` es el `event_id` que manda el productor. Tiene tres problemas:

- **Colisión entre clientes.** El PK es global y no incluye `client_id`. Si dos clientes generan el mismo `event_id` (los datos de ejemplo usan `EVT001`, `EVT002`...), el segundo evento se descarta como duplicado y esa notificación se pierde sin aviso.
- **No hay identidad propia.** El enunciado pide `GET /notification_events/{notification_event_id}`: la notificación es una entidad del sistema, distinta del evento de transacción que la originó, pero hoy no tiene identidad propia.
- **PK aleatorio y largo.** Los `event_id` del simulator son `EVT-<UUIDv4>` en un `VARCHAR(64)`. En el índice clusterizado de InnoDB cada inserción cae en una página al azar, y cada índice secundario arrastra hasta 64 bytes de PK.

Además, la paginación por keyset que viene después (`notification-event-pagination`) necesita un desempate único y compacto para el orden por `created_at`.

## What Changes

- `notification_event` pasa a tener la columna `id` UUIDv7, generada por la base, como PK. `event_id` queda como dato del productor, con unicidad por `(client_id, event_id)`.
- La idempotencia del notificator pasa a ser por `(client_id, event_id)`. Un mismo `event_id` de dos clientes distintos genera dos notificaciones independientes, una por cliente.
- Toda escritura y lectura del notificator sobre una fila la identifica por `(client_id, event_id)`. Los mensajes de Kafka (principal, retry, DLT y replay) no cambian de formato ni de key.
- **BREAKING (API del manager):** `{notification_event_id}` en `GET /notification_events/{notification_event_id}` y en `POST /notification_events/{notification_event_id}/replay` pasa a ser el `id` nuevo. Un `event_id` en ese path responde 404.
- La representación de un evento en la API agrega `notification_event_id` y conserva `event_id`.
- El cuerpo del webhook agrega `notification_event_id`, y la petición agrega el header `X-Notification-Event-Id`. `event_id` y `X-Event-Id` se mantienen, así que el cambio es compatible para los receptores.

Aplicaciones afectadas: **notificator** (schema, repositorio, entrega, webhook) y **manager** (repositorio, búsqueda por `id`, DTO de respuesta). **gateway**: no se toca.

Fuera de alcance:
- La paginación del listado. Va en un cambio aparte (`notification-event-pagination`), que se apoya en este.
- Cambiar el formato o la key de cualquier tópico de Kafka.
- Migrar datos de una base existente. Los scripts de `notificator/db/` solo corren con un volumen nuevo, así que en local se recrea el entorno.
- Correlacionar logs por `notification_event_id` en todos los puntos del flujo. Solo se agrega donde ya hay una fila leída (ver design).

## Capabilities

### New Capabilities

_Ninguna._

### Modified Capabilities

- `notification-delivery`: el claim del primer intento y la detección de duplicados pasan a ser por `(client_id, event_id)` y generan un `notification_event_id`. El cuerpo y los headers del webhook incluyen ese id.
- `notification-event-query`: la representación incluye `notification_event_id`, el detalle se busca por ese id, y un id mal formado responde 404.
- `notification-event-replay`: el endpoint busca el evento por `notification_event_id` y `client_id`, y el pedido publicado toma el `event_id` de la fila.

## Impact

- `notificator/db/01-schema.sql`: columna `id`, nuevo PK y `UNIQUE (client_id, event_id)`. `03-seed-notification-events.sql` no cambia, porque el `DEFAULT` genera los ids.
- notificator: `JdbcNotificationEventRepository` (todas las sentencias se filtran por `client_id` y `event_id`, e `insertClaim` devuelve el id), `NotificationEventRepository`, `NotificationEvent`, `DeliveryService`, `ExhaustedEventService`, `StaleDeliveryRecoveryService`, `WebhookClient` y `RestClientWebhookClient`, junto con sus tests.
- manager: `JdbcNotificationEventRepository`, `NotificationEvent`, `NotificationEventResponse`, `NotificationEventReplayService` y `NotificationEventQueryService`, junto con sus tests.
- Documentación: `README.md` (idempotencia y ejemplos con `EVT001` en el path), `docs/flujo-notificator.mmd` y el `context` de `openspec/config.yaml` ("INSERT sobre la PK", "Idempotente por event_id").
- No hay dependencias nuevas. Requiere MariaDB 11.7 o posterior por el tipo `UUID` y `UUID_v7()`; el compose usa 12.3.3.
