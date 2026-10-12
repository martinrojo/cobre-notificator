# Design

## Context

Ver proposal.md para la motivación. Estado actual del listado en el manager:

```
NotificationEventController.list(NotificationEventsRequest)
  -> ListNotificationEventsUseCase.list(NotificationEventFilter)
  -> NotificationEventRepository.findAll(filter)
       SELECT ... WHERE client_id = :clientId [from/to/status] ORDER BY created_at DESC   (sin LIMIT)
  -> List<NotificationEventResponse>  (array JSON)
```

Restricciones que condicionan el diseño:
- `created_at` es `DATETIME(3)` y lo define el productor del evento, no el orden de inserción, así que puede haber empates. `id` es UUIDv7 y es único.
- Índices existentes: `idx_event_client_created (client_id, created_at)` e `idx_event_client_status_created (client_id, delivery_status, created_at)`. En InnoDB cada índice secundario incluye la PK, así que en la práctica son `(..., created_at, id)` y sirven para `ORDER BY created_at DESC, id DESC` sin cambios de esquema.
- El manager solo tiene `SELECT` sobre `notification_event`, y este cambio no necesita más permisos.
- El delta de `notification-event-query` parte de la versión que deja `notification-event-own-id` (agrega `notification_event_id`). Ese cambio se archiva antes que este.

## Goals / Non-Goals

**Goals:**
- Que cada request lea a lo sumo `size + 1` filas, con costo constante sin importar la profundidad de la página.
- Un orden total y estable entre páginas.
- Rechazar con 400, antes de consultar la DB, todo cursor que no corresponda al cliente o a los filtros del request.

**Non-Goals:**
- Página anterior, total de resultados y salto a la página N.
- Expiración del cursor. No guarda estado en el servidor y sigue sirviendo mientras exista la posición.

## Decisions

### 1. Keyset en vez de OFFSET
`ORDER BY created_at DESC, id DESC` con el predicado:

```sql
AND (:afterCreatedAt IS NULL
     OR created_at < :afterCreatedAt
     OR (created_at = :afterCreatedAt AND id < :afterId))
ORDER BY created_at DESC, id DESC
LIMIT :limit
```

- **Alternativa OFFSET/LIMIT:** se descarta porque el costo crece con la profundidad (escanea y descarta filas) y porque, si el notificator inserta mientras el cliente pagina, la ventana se corre y aparecen duplicados u omisiones.
- **Predicado expandido en vez de la tupla `(created_at, id) < (:c, :i)`:** MariaDB no siempre usa el índice para comparar tuplas, y la forma expandida es un rango sobre el índice.
- **`LIMIT size + 1`:** si vuelven `size + 1` filas, hay página siguiente. Se descarta la última fila y el cursor se arma con la fila `size`. Así no hace falta `COUNT` ni una segunda query.

### 2. El cursor lleva la posición, el cliente y los filtros
El modelo de dominio es `PageCursor(Instant createdAt, String id, NotificationEventFilter filter)`. `NotificationEventFilter` ya tiene `clientId`, `from`, `to` y `deliveryStatus`.

Validación en el servicio, antes de consultar:
1. Si `cursor.filter().clientId()` no coincide con el cliente identificado, se rechaza con `'cursor' is invalid`. Es el mismo mensaje que un cursor mal formado, para no revelar que el token pertenece a otro cliente.
2. Si `cursor.filter()` no es igual (`equals`) al filtro del request, se rechaza con `'cursor' does not match the current filters`. Los records comparan `Instant` y enums por valor, así que fechas equivalentes en distinta zona y status en distinta capitalización ya llegan iguales, porque el parseo existente los normaliza. No hace falta lógica extra para normalizar.

`size` no forma parte del cursor, así que puede cambiar entre páginas.

- **Alternativa:** guardar solo un hash de los filtros. Se descarta porque no ahorra nada y hace más difícil depurar un cursor.

### 3. Codificación: base64url de JSON, en el adaptador web
El formato del token es un detalle del adaptador web. Un `CursorCodec` en `infrastructure/adapter/in/web` serializa `PageCursor` a JSON con el `JsonMapper` de Spring (inyectado igual que en `KafkaReplayRequestPublisher`) y lo codifica en base64url sin padding. Al decodificar, cualquier falla (base64, JSON, campos faltantes, UUID o fecha inválidos) termina en `InvalidParameterException` con `'cursor' is invalid`. No hay dependencias nuevas.

### 4. Sin firma (HMAC)
El cursor no es una barrera de seguridad: la query siempre filtra por el `client_id` del contexto autenticado y nunca por el del cursor. Manipular el token solo puede mover la posición dentro de los propios datos del cliente, o hacer que lo rechacen. Con respecto a OWASP A01 (Broken Access Control), el aislamiento sigue dependiendo del `client_id` autenticado, y la comparación del paso 2.1 es una segunda barrera. Firmar agregaría gestión y rotación de claves sin proteger nada adicional.

### 5. size: parseo en el DTO, rango en el servicio
- `NotificationEventsRequest` recibe `size` como `String` y lo parsea igual que `from` y `to`. Si no es numérico, lanza `InvalidParameterException` con un mensaje que nombra `size`. Así no hace falta tocar el handler de errores de binding.
- `NotificationEventQueryService` recibe `manager.query.default-page-size` (20) y `manager.query.max-page-size` (100) por `@Value`, igual que `ApiExceptionHandler` con `manager.replay.retry-after`. Aplica el valor por defecto cuando `size` es nulo y valida `1..max`; fuera de rango lanza `'size' must be between 1 and <max>`.
- **Alternativa `@ConfigurationProperties`:** se descarta porque son dos valores y el proyecto ya usa `@Value`.

### 6. Forma de puertos y respuesta
- `ListNotificationEventsUseCase.list(NotificationEventFilter filter, PageCursor cursor, Integer size)` devuelve `NotificationEventPage(List<NotificationEvent> events, PageCursor next)`, con `next` nulo en la última página. `cursor` es nulo en la primera página.
- `NotificationEventRepository.findAll(filter, after, limit)`, donde `after` es el `PageCursor` o `null`. El adaptador usa `createdAt` e `id` del cursor. El `client_id` y los filtros de la query siempre salen de `filter`.
- Web: `NotificationEventPageResponse(List<NotificationEventResponse> data, String nextCursor)`. Con `SNAKE_CASE` se serializa como `next_cursor`, y el `null` se incluye.

## Risks / Trade-offs

- [Breaking change en la forma del listado] → la API todavía no tiene consumidores externos. Se actualizan la spec, el README y los tests en el mismo cambio.
- [Si un evento cambia de `delivery_status` mientras se pagina con ese filtro, puede salir del resultado o entrar en él] → es aceptable. El orden es por `created_at`, que no cambia, así que nunca se repite una fila ya vista; a lo sumo se pierde una que cambió de estado.
- [Un evento nuevo con `created_at` antiguo (el productor manda fechas pasadas) aparece en páginas posteriores y no en la primera] → es consistente con el orden pedido y queda documentado en el escenario de la spec.
- [El predicado keyset no tiene test contra MariaDB, porque los tests del repositorio son unitarios] → se verifica a mano con `docker compose` y el seed (task 4.2), mirando el `EXPLAIN`.

## Migration Plan

Sin migración de datos ni de esquema. Se despliega el manager nuevo, y para hacer rollback se despliega la versión anterior. Los cursores emitidos dejan de servir en ese caso, pero no tienen estado que limpiar.
