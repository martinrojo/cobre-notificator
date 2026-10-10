# Design

## Context

Estado actual del manager:
- Hexagonal, con `NotificationEventQueryService` y el port out `NotificationEventRepository`.
- `findById(clientId, eventId)` ya filtra por `client_id` en la misma consulta.
- `ApiExceptionHandler` ya devuelve `ProblemDetail` para 400, 401 y 404.
- No tiene Kafka, actuator ni métricas.

El usuario de DB del manager solo tiene SELECT sobre `notification_event`. `notificator.replay.v1` existe en `create-topics.sh`, pero nadie lo produce ni lo consume todavía. Motivación y alcance: ver proposal.md. Comportamiento esperado: ver `specs/notification-event-replay/spec.md`.

## Goals / Non-Goals

**Goals:**
- Que un hilo HTTP nunca quede bloqueado más de `max.block.ms + publish-timeout` si Kafka está caído.
- Que la decisión por estado sea una función pura del dominio, testeable sin Spring ni Kafka.

**Non-Goals:**
- Garantizar una sola publicación por pedido. El notificator descarta duplicados con su transición condicional.
- Un productor transaccional. No hace falta atomicidad entre DB y Kafka porque el manager no escribe en la DB.

## Decisions

### 1. La decisión de replay vive en el dominio
`NotificationEvent` gana el campo `lastReplayAt` y un método `replayDecision()`, que devuelve un enum `ReplayDecision { PUBLISH, ALREADY_IN_PROGRESS, NOT_ALLOWED }`:

| `delivery_status` | `last_replay_at` | Decisión |
|---|---|---|
| `FAILED` | cualquiera | `PUBLISH` |
| `PENDING`, `RETRYING` | no nulo | `ALREADY_IN_PROGRESS` |
| `PENDING`, `RETRYING` | nulo | `NOT_ALLOWED` |
| `DELIVERED`, `NOT_SUBSCRIBED` | cualquiera | `NOT_ALLOWED` |

El servicio de aplicación (`NotificationEventReplayService`, use case `ReplayNotificationEventUseCase`) hace esto:
1. Busca con `repository.findById(clientId, eventId)`. Si no encuentra el evento, lanza `NotificationEventNotFoundException`.
2. Según la decisión, publica, retorna, o lanza `ReplayNotAllowedException.of(eventId, status)`.

- Alternativa: un `switch` en el servicio. Se descarta porque la regla es del dominio y en un método del record se testea con una tabla de casos.
- Alternativa: un `UPDATE ... WHERE delivery_status='FAILED'` para deduplicar en el manager. Se descarta porque el manager no tiene permiso de escritura (Context).

### 2. Una sola consulta: se reutiliza `findById`
Se agrega `last_replay_at` al `SELECT` compartido y al `ROW_MAPPER`. `findById` ya filtra por `event_id AND client_id`. Así, un evento ajeno da el mismo `Optional.empty()` que uno inexistente, y su estado nunca se lee (spec, Aislamiento por cliente en el replay). No se agrega ninguna consulta nueva.

El listado y el detalle también leen la columna, pero no la exponen: `NotificationEventResponse` no cambia.

### 3. Port out `ReplayRequestPublisher` y adapter Kafka síncrono
- Port: `void publish(ReplayRequested request)`, con `record ReplayRequested(String eventId, String clientId, Instant requestedAt)` en `domain/model`.
- Si no se pudo confirmar la publicación, lanza `ReplayPublishException` (dominio).
- Adapter `KafkaReplayRequestPublisher`: `kafkaTemplate.send(topic, clientId, json).get(publishTimeout)`.
  - `ExecutionException`, `TimeoutException` y `KafkaException` se envuelven en `ReplayPublishException`. Un `KafkaException` sale si `send()` agota `max.block.ms` esperando metadata.
  - Con `InterruptedException` se restaura el flag de interrupción y también se lanza `ReplayPublishException`.
- `requested_at` sale de un bean `Clock` (UTC) que se inyecta en el servicio. Así los tests fijan el instante.

Se eligió `get(timeout)` sobre el `CompletableFuture` porque la spec exige el 202 solo con ack. Una publicación async con callback no permite responder 503.

### 4. Serialización: JSON a `String` con el `JsonMapper` de Boot
El adapter serializa el record con el `JsonMapper` (Jackson 3) que autoconfigura Boot. Ese mapper ya usa `SNAKE_CASE` (por `spring.jackson`) y escribe los `Instant` como ISO-8601. El productor usa `StringSerializer` para key y value.
- Alternativa: `JacksonJsonSerializer` de spring-kafka. Se descarta porque por defecto agrega el header `__TypeId__` con el nombre de la clase Java. Eso acopla al consumidor a un tipo del manager y expone detalles internos.

### 5. Configuración del productor
```yaml
spring.kafka:
  bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9094}
  producer:
    acks: all
    key-serializer: org.apache.kafka.common.serialization.StringSerializer
    value-serializer: org.apache.kafka.common.serialization.StringSerializer
    properties:
      enable.idempotence: true
      max.block.ms: ${KAFKA_MAX_BLOCK_MS:1000}
      request.timeout.ms: 2000
      delivery.timeout.ms: 3000
manager.replay:
  topic: notifications.replay-requested.v1
  publish-timeout: ${REPLAY_PUBLISH_TIMEOUT:3s}
  retry-after: ${REPLAY_RETRY_AFTER:5s}
```
- `delivery.timeout.ms` (3 s) ≥ `request.timeout.ms` + `linger.ms`, como exige Kafka, y ≤ `publish-timeout`. Así el productor deja de reintentar más o menos cuando el request HTTP se rinde, en vez de seguir reintentando en segundo plano durante los 120 s del default.
- `enable.idempotence` y `acks=all` ya son el default del cliente Kafka 4. Se dejan explícitos porque forman parte del contrato del cambio.
- `topic` y `publish-timeout` se bindean en un `@ConfigurationProperties` record `ReplayProperties` (adapter de mensajería). `retry-after` se inyecta con `@Value` en `ApiExceptionHandler`: así el adapter web no depende del de mensajería y los tests `@WebMvcTest` no necesitan el record.

### 6. Problem Details para 409 y 503
`ApiExceptionHandler` suma dos handlers:
- `ReplayNotAllowedException` → 409, `detail`: `"Notification event cannot be replayed in status <STATUS>"`. El estado se puede revelar porque el evento es del cliente.
- `ReplayPublishException` → 503 con `ResponseEntity<ProblemDetail>`, header `Retry-After: <segundos de retry-after>` y `detail`: `"Replay could not be requested, try again later"`. La causa técnica va solo al log.

El 404 reutiliza el handler existente. Los Problem Details de Spring ya salen con `Content-Type: application/problem+json`.

### 7. Endpoint
`@PostMapping("/{notification_event_id}/replay")` en `NotificationEventController` devuelve `ResponseEntity.accepted().build()`. El 202 es el mismo para `PUBLISH` y `ALREADY_IN_PROGRESS`: el cliente no necesita distinguirlos.

### 8. Logs y métricas
- INFO `Replay requested event_id={} client_id={}` después del ack.
- DEBUG cuando el replay ya está en curso o no se permite.
- ERROR `Replay publish failed event_id={}`, con la excepción, en un 503.
- Nunca se loguea `content`. El mensaje tampoco lo lleva.
- Counter Micrometer `manager.replay.requests` con tag `outcome`: `published`, `in_progress`, `not_allowed`, `not_found`, `unavailable`. No lleva tag `client_id`, para no disparar la cardinalidad; el detalle por cliente queda en los logs.
- Dependencias: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`, ambas parte del stack (Micrometer + Prometheus).
  - Actuator corre en un puerto de management separado (`management.server.port: 9291`) que solo expone `prometheus` y `health`.
  - Ese puerto no se publica en el compose ni se rutea desde el gateway.
  - Los filtros servlet del contexto principal no aplican al contexto de management en otro puerto, así que no hay que tocar `PUBLIC_PATHS`. Una tarea verifica esto.

### 9. Esquema e infraestructura
- `notificator/db/01-schema.sql`: `last_replay_at DATETIME(3) NULL` en `notification_event`, después de `last_attempt_at`.
  - Va en el script del notificator, que es dueño del esquema; ninguna app tiene DDL.
  - El grant SELECT del manager cubre la columna.
  - El seed no cambia: queda nulo.
- `kafka/create-topics.sh`: `notifications.replay-requested.v1` reemplaza a `notificator.replay.v1`.
- `docker-compose.yml`: `manager` recibe `KAFKA_BOOTSTRAP_SERVERS: kafka:9092` y `depends_on: kafka-init: condition: service_completed_successfully`. Así el tópico existe antes del primer pedido; la auto-creación está deshabilitada.

### 10. Seguridad (OWASP Top 10)
- **A01 Broken Access Control (IDOR):** el `client_id` sale del filtro, nunca del request, y va en la misma consulta que el `event_id`. Un evento ajeno da 404 indistinguible: ni su existencia ni su estado se filtran por la diferencia entre 404 y 409.
- **A04 Insecure Design / agotamiento de recursos:** `max.block.ms` y `publish-timeout` acotan el tiempo de cada hilo HTTP si Kafka cae. Sin esos límites, un broker caído bloquearía hasta 60 s por request y dejaría sin hilos al resto de la API. El abuso por volumen (spam de replays) se mitiga con el rate limiting del gateway, que queda fuera de alcance.
- **A09 Logging Failures:** cada pedido queda registrado con `event_id` y `client_id`, sin `content`.

## Risks / Trade-offs

- [Un timeout no significa que no se publicó: el broker puede confirmar después del 503 y el reintento del cliente publica de nuevo] → Duplicado aceptado: el notificator descarta replays duplicados con su transición condicional sobre `FAILED`.
- [Ventana entre la publicación y el momento en que el notificator toma el replay: el evento sigue `FAILED` y cada pedido publica de nuevo] → Mismo mitigante. El rate limiting del gateway acota el volumen.
- [Si cambia el esquema hay que recrear el volumen de MariaDB, y se pierden datos locales] → Solo entorno local con datos seed. Se documenta en el README.
- [El manager ahora depende de Kafka para arrancar en el compose] → Solo el replay usa Kafka. `max.block.ms` hace que el resto de la API siga respondiendo aunque el broker caiga después del arranque.
- [El contrato del mensaje no tiene schema registry] → El JSON tiene 3 campos y el tópico está versionado (`.v1`). Un cambio incompatible va a un `.v2`.

## Migration Plan

1. `docker compose down -v`, para recrear MariaDB con la columna nueva y Kafka con el tópico nuevo.
2. `docker compose up --build`.

Rollback: revertir el commit y repetir los dos pasos. Nadie consume todavía el tópico, así que no hay mensajes que migrar.
