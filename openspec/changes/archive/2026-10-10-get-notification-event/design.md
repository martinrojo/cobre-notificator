# Design

## Context

El manager ya tiene el flujo del listado (`ListNotificationEventsUseCase` → `NotificationEventQueryService` → `NotificationEventRepository` → `JdbcNotificationEventRepository`), el `ClientIdentificationFilter` que protege toda ruta salvo `/ping`, `ClientContext` y `ApiExceptionHandler`, que responde con `ProblemDetail`. La tabla tiene PK `event_id`. Requisitos: ver el delta de `notification-event-query`.

## Goals / Non-Goals

**Goals:**
- Reusar las piezas existentes; agregar solo lo que el caso por id necesita.
- Que sea imposible devolver un evento ajeno, aunque haya un bug en las capas superiores.

**Non-Goals:**
- Exponer campos nuevos en la respuesta.

## Decisions

### Aislamiento en el SQL, no en Java (OWASP A01, Broken Access Control / IDOR)
```sql
SELECT event_id, client_id, event_type, content, delivery_status, created_at, last_attempt_at
FROM notification_event
WHERE event_id = :eventId AND client_id = :clientId
```
`NotificationEventRepository.findById(String clientId, String eventId)` devuelve `Optional<NotificationEvent>`. Al filtrar por client_id en la misma consulta, un evento ajeno simplemente no existe para el cliente: no hay una rama "existe pero es de otro" que pueda filtrar información o tardar distinto.
Alternativa descartada: buscar por `event_id` y comparar `clientId` en el servicio. Lleva el dato ajeno a memoria y abre la puerta a responder 403 o a diferencias de comportamiento entre los dos casos.
El repositorio reusa la constante `ROW_MAPPER` y las columnas del listado.

### Use case separado
`GetNotificationEventUseCase { NotificationEvent get(String clientId, String eventId) }`, implementado por el mismo `NotificationEventQueryService`, que ya es el servicio de consultas. El servicio hace `findById(...).orElseThrow(() -> NotificationEventNotFoundException.of(eventId))`.
Alternativa descartada: un servicio nuevo. Duplica la inyección del repositorio sin ganar nada; los dos use cases son consultas sobre el mismo agregado.

### 404 sin el id en el mensaje
- `NotificationEventNotFoundException.of(eventId)` vive en `domain.exception`. Guarda el id para logs de DEBUG, pero su mensaje público es fijo: `Notification event not found`.
- `ApiExceptionHandler` la traduce a `ProblemDetail` 404 con ese `detail`.
- El `instance` del `ProblemDetail` refleja el path pedido, que el cliente ya conoce, así que no revela nada nuevo.

### Path variable sin validación de formato
`@PathVariable("notification_event_id") String eventId`. Un id que no existe (incluido uno de más de 64 caracteres) cae en el 404 por la consulta, sin necesidad de validación extra. El valor siempre viaja como parámetro bindeado, nunca concatenado.

### Logs
No hay log en INFO, porque una consulta no es una transición de estado. En DEBUG se loguea `event_id` y si se encontró. El client_id ya va por el MDC y `content` no se loguea nunca.

## Risks / Trade-offs

- [El SQL por id no tiene test automatizado (Testcontainers está fuera del stack)] → Se verifica con `curl` contra el compose, con los escenarios de la spec, incluido el caso de evento ajeno.
- [`delivery_date` mapea `last_attempt_at`; para un evento `PENDING` es `null`] → Es el mismo contrato que el listado, ya documentado en la spec.
