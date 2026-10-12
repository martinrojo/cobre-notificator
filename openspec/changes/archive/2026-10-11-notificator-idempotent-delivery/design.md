# Design

## Context

Estado actual del notificator:
- Solo existe `NotificatorApplication`. El `pom.xml` ya trae kafka, jdbc, webmvc, actuator, Micrometer/Prometheus y resilience4j.
- El usuario de DB `notificator` tiene SELECT sobre `subscription` y SELECT/INSERT/UPDATE sobre `notification_event`, sin DELETE.
- `notification_event` tiene PK `event_id`, `attempts`, `updated_at` (`ON UPDATE CURRENT_TIMESTAMP(3)`), `last_*`, `delivery_url` y `last_replay_at`.
- Los tópicos ya existen (spec `event-topics`). La key del principal es `client_id`, así que los mensajes de un mismo evento caen en la misma partición y se procesan en serie.

Motivación: ver proposal.md. Comportamiento: ver `specs/notification-delivery/spec.md`.

Comportamiento de spring-kafka 4.1.1, verificado en sus sources:
- `KafkaBackoffAwareMessageListenerAdapter`: si falta el header de backoff, el mensaje se procesa de inmediato.
- `DefaultDestinationTopicResolver`: el tópico siguiente se resuelve por el tópico actual, no por el header de attempts. Si ese header falta, vale 1.
- `RetryTopicConfigurationSupport.configureBlockingRetries` permite combinar reintentos bloqueantes y no bloqueantes.

## Goals / Non-Goals

**Goals:**
- Una sola cadena de entrega activa por evento y un solo POST por intento reclamado.
- Ningún evento queda trabado en `PENDING` o `RETRYING`, ni se pierde por una caída de la DB.
- La idempotencia la resuelven la PK y las escrituras condicionales, sin locks distribuidos ni tablas extra.

**Non-Goals:**
- Exactly-once hacia el webhook. Un crash entre la respuesta 2xx y el `UPDATE` produce un POST repetido, que el cliente deduplica por `X-Event-Id`.
- Orden de entrega entre eventos del mismo cliente una vez que alguno entra en retry.

## Decisions

### 1. El claim va antes del POST y `attempts` es el fencing token

`attempts` = intentos **iniciados**. Toda escritura de estado es condicional sobre ese valor:

```sql
-- intento 1 (tópico principal): claim por PK
INSERT INTO notification_event (event_id, client_id, event_type, content, delivery_status, attempts, created_at)
VALUES (?, ?, ?, ?, 'PENDING', 1, ?)            -- DuplicateKeyException => duplicado

-- intento n >= 2 (retry): claim condicional
UPDATE notification_event SET attempts = :n
 WHERE event_id = :id AND attempts = :n - 1 AND delivery_status IN ('PENDING','RETRYING')

-- resultado no exitoso del intento n
UPDATE ... SET delivery_status = 'RETRYING' | 'FAILED' | 'NOT_SUBSCRIBED', last_* = ...
 WHERE event_id = :id AND attempts = :n AND delivery_status IN ('PENDING','RETRYING')

-- éxito: sin condición sobre attempts (el éxito siempre gana)
UPDATE ... SET delivery_status = 'DELIVERED', ...
 WHERE event_id = :id AND delivery_status IN ('PENDING','RETRYING')
```

Si una escritura condicional afecta 0 filas, otro proceso avanzó la fila: el resultado se descarta, se hace ACK y nunca se lanza la excepción reintentable. Eso evita que un intento superado arme una cadena paralela.

Alternativas descartadas:
- CAS después del POST (diagrama original). Dos mensajes del mismo intento hacen POST los dos, y el perdedor arma una segunda cadena al lanzar la excepción.
- `INSERT IGNORE`. MariaDB convierte también errores de datos (truncamiento) en warnings. Se usa un `INSERT` plano y se trata `DuplicateKeyException` como duplicado.
- Reiniciar `attempts` en la recuperación. Produce ABA: un mensaje viejo vuelve a encontrar el valor que espera y reclama.

### 2. El número de intento sale del tópico

| Tópico de origen | Intento que ejecuta | `attempts` esperado antes del claim |
|---|---|---|
| `transactions.events.v1` | 1 | (no existe la fila) |
| `notificator.retry.v1-i` | i + 2 | i + 1 |
| `notificator.dlt.v1` | el que falló: header `retry_topic-attempts` − 1 | — |

Una sola función hace el mapeo `topic → attempt`, y su inversa (`attempt k → retry.v1-(k-1)`) la usa el job. En el DLT el tópico no alcanza: Spring deja como `kafka_original-topic` el principal aunque el fallo venga de un retry. Ahí se usa el header `retry_topic-attempts` de Spring, que vale el intento fallido + 1 (sin header, el intento 1). El job lo setea en lo que publica (`k + 1`, el intento que ejecutará el mensaje), igual que Spring en un retry normal. `X-Delivery-Attempt` sale del `attempts` reclamado.

### 3. Los datos de la entrega salen de la fila

En los reintentos, el `client_id`, el `event_type` y el `content` se leen de la fila, no del mensaje. El mensaje solo aporta el `event_id`. La fila es la única fuente de verdad, y un mensaje con un `client_id` alterado no puede desviar la entrega (OWASP A01). Por eso a la recuperación le alcanza con reconstruir el mensaje desde la fila.

### 4. Clasificación de errores: tres caminos

| Error | Camino |
|---|---|
| Resultado reintentable del webhook con CAS ganado | `RetryableDeliveryException` → `@RetryableTopic` (`include`) → retry n / DLT |
| `TransientDataAccessException`, `DataAccessResourceFailureException`, `RecoverableDataAccessException` | reintento **bloqueante** (`configureBlockingRetries`), backoff exponencial hasta 30 s, sin límite de intentos |
| Mensaje inválido, cualquier otra excepción | directo al DLT |

El reintento bloqueante garantiza que un mensaje solo entra a un tópico de retry **después** de un claim exitoso. Con eso, el listener de retry siempre encuentra la fila: si no la encuentra, es una anomalía y se registra en un log ERROR con ACK. Sin el reintento bloqueante, una DB caída en el `INSERT` mandaría el evento al DLT sin fila y se perdería.

Solo se reintentan las excepciones transitorias o de recurso. `DataIntegrityViolationException` es determinista, así que no se reintenta: bloquearía la partición para siempre. Los largos se validan al parsear (spec, Mensaje del tópico principal).

### 5. Job de recuperación: reanuda sin tocar `attempts`

`@Scheduled(fixedDelay = recovery.interval)` en cada instancia:

```sql
-- candidatos; el índice (delivery_status, updated_at) filtra por el umbral mínimo (lease)
SELECT event_id, client_id, event_type, content, created_at, attempts
  FROM notification_event
 WHERE delivery_status IN ('PENDING','RETRYING')
   AND updated_at < NOW(3) - INTERVAL :lease SECOND
 LIMIT :batch

-- por fila, el umbral exacto sale de attempts: delay(1..4) = 60, 300, 1500, 0
UPDATE notification_event SET updated_at = NOW(3)
 WHERE event_id = :id AND attempts = :k AND delivery_status IN ('PENDING','RETRYING')
   AND updated_at < NOW(3) - INTERVAL (:delayK + :lease) SECOND
```

- Si el `UPDATE` afecta 1 fila y `k < 4`, se publica en `notificator.retry.v1-(k-1)` con key `client_id` y un valor con el formato de `transactions.events.v1`. El envío es síncrono (`get(timeout)`).
- Si `k = 4`, en lugar de refrescar, un CAS equivalente pasa a `FAILED`.
- Si el `UPDATE` afecta 0 filas, la fila la tomó otra instancia o avanzó, y se saltea.

Propiedades de este diseño:
- **El job nunca reclama un intento.** El reclamo lo hace el consumer de retry con su CAS normal, así que un mensaje del job y uno demorado compiten como cualquier duplicado.
- **Varias instancias no necesitan lock.** El refresh de `updated_at` vuelve a evaluar el umbral, y la segunda instancia no lo cumple.
- **El tiempo es el de la DB** (`NOW(3)`), así el drift de reloj entre instancias no afecta.
- **Si la publicación falla**, la fila queda refrescada y se reintenta en el próximo umbral, con un log ERROR.

La recuperación se demora como máximo `delay(k) + lease + interval`. Valores por defecto: `lease = 120 s` (mayor que connect + read timeout del webhook más el lag esperable) e `interval = 60 s`.

Alternativas descartadas:
- Takeover por lease en el consumer del principal. No alcanza cuando la reentrega llega antes de que venza el lease, y sumaría un segundo mecanismo.
- Marcar `FAILED` y publicar un replay. El cliente vería un fallo inexistente, `last_replay_at` mentiría y el notificator pasaría a producir un contrato del manager.
- Republicar en `transactions.events.v1`. Exigiría que el notificator pueda producir en un tópico de la plataforma (ACL), y el claim por `INSERT` lo descartaría como duplicado.
- ShedLock o similar. Agrega una dependencia y una tabla, y el CAS ya resuelve el problema.

### 6. Webhook: `RestClient` sin redirecciones

`RestClient` sobre `JdkClientHttpRequestFactory`, con `HttpClient.Redirect.NEVER` y timeouts configurables (connect 2 s, read 10 s). El adapter devuelve un resultado: código HTTP, o error de transporte para timeout o conexión, nunca una excepción por status. La clasificación 2xx / reintentable / permanente es una función pura del dominio. La latencia se mide alrededor de la llamada.

Si `webhook_url` no es `https` (y `notificator.webhook.allow-http=false`), el intento termina en `FAILED` sin POST.

### 7. Estructura hexagonal

```
domain/
  model/      TransactionEvent, NotificationEvent, Subscription, DeliveryStatus,
              WebhookResult, DeliveryOutcome (clasificación), RetrySchedule (delay(k))
  exception/  RetryableDeliveryException.of(eventId, attempt), InvalidEventException.of(reason)
  port/in/    DeliverEventUseCase (first(event) / retry(eventId, attempt)),
              FailExhaustedEventUseCase, RecoverStaleDeliveriesUseCase
  port/out/   NotificationEventRepository, SubscriptionRepository, WebhookClient,
              RetryPublisher
application/  DeliveryService, ExhaustedEventService, StaleDeliveryRecoveryService
infrastructure/adapter/
  in/kafka/       TransactionEventListener (@RetryableTopic + @DltHandler), TopicAttempts
  in/scheduling/  StaleDeliveryRecoveryJob
  out/persistence/ JdbcNotificationEventRepository, JdbcSubscriptionRepository
  out/http/       RestClientWebhookClient
  out/messaging/  KafkaRetryPublisher
config/           KafkaRetryConfig (names provider, blocking retries), DeliveryProperties
```

`TransactionEventListener` parsea el valor a `String` con el `JsonMapper` de Boot (`SNAKE_CASE`), igual que el manager, sin el header `__TypeId__`. El `RetryTopicNamesProviderFactory` propio devuelve `notificator.retry.v1-<n>` y `notificator.dlt.v1`, con `autoCreateTopics = "false"`. El consumer usa `AckMode.RECORD` y `concurrency = 3`, y el producer `acks=all` con idempotencia.

### 8. Métricas y logs

| Métrica | Tags |
|---|---|
| `notificator.deliveries` (counter) | `outcome` = delivered / retrying / failed / not_subscribed, `client_id` |
| `notificator.discarded` (counter) | `reason` = duplicate / stale |
| `notificator.dlt.messages` (counter) | `reason` = exhausted / invalid / unexpected |
| `notificator.webhook.latency` (timer) | `outcome` |
| `notificator.recovery.events` (counter) | `action` = republished / failed |

Niveles de log:
- INFO: `DELIVERED` y `FAILED`.
- ERROR: intento que se reintenta, DLT, publicación fallida del job y retry sin fila.
- DEBUG: descartes, request y response.

`event_id`, `client_id` y `attempt` van como campos estructurados. `content` no aparece nunca.

### 9. Esquema

En `notificator/db/01-schema.sql`, igual que en cambios anteriores: `CREATE INDEX idx_event_status_updated ON notification_event (delivery_status, updated_at);`. No se agregan columnas ni permisos: `updated_at` y `attempts` alcanzan.

## Risks / Trade-offs

- **Un intento perdido cuenta como hecho.** Si una instancia cae con el intento k en vuelo, la recuperación sigue con el k+1, y un evento puede terminar `FAILED` con un intento menos de los efectivos. → Es conservador y respeta el tope de 4. El cliente puede pedir un replay.
- **POST repetido** si hay un crash entre el 2xx y el `UPDATE`, o si un zombie y el intento siguiente se solapan. → La entrega es at-least-once por diseño: va `X-Event-Id` y lo documentamos.
- **`FAILED` con entrega exitosa en el último intento.** Si la DB cae entre el 2xx del intento 4 y el `UPDATE`, el resultado se pierde y la recuperación marca `FAILED`. → Límite conocido: el job no puede saber que el POST salió bien, y el cliente igual recibió el evento. Solo pasa si la caída coincide con esa ventana del último intento.
- **Partición bloqueada mientras la DB está caída.** → Es intencional: sin DB no se puede avanzar. El backoff está acotado a 30 s y el consumer sigue haciendo poll, así que no hay rebalance.
- **Mensaje venenoso por un error de DB no transitorio clasificado como transitorio.** → La lista de excepciones bloqueantes es cerrada, y los largos se validan antes del `INSERT`.
- **Orden por cliente.** Un evento en retry deja pasar a los siguientes del mismo cliente. → Fuera de alcance. El webhook recibe `created_at`.
- **Tópicos de retry compartidos con el futuro listener de replay.** Si los dos `@RetryableTopic` consumen los mismos tópicos con distinto group, cada mensaje se procesa dos veces. → El CAS lo vuelve inocuo (uno gana y el otro descarta), pero el cambio del replay tiene que resolverlo con un solo consumer de retry y mantener `attempts` monótono.
- **SSRF (OWASP A10:2021).** La URL sale de `subscription`, nunca del evento. Se exige `https` y no se siguen redirecciones. En producción, además, habría que limitar el egress y bloquear rangos privados (solo documentado).
- **Fuga de datos en logs (OWASP A09:2021).** `content` no se loguea ni en DEBUG, y `last_error` se trunca y nunca incluye el cuerpo de la respuesta.

## Migration Plan

Es un entorno local sin datos productivos:
1. `docker compose down -v` para recrear MariaDB con el índice.
2. `docker compose up`.

Rollback: volver a la imagen anterior del notificator. El índice es inocuo.
