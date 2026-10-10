# Spec Delta

## Purpose

Permite que un cliente consulte en modo self-service sus notificaciones de eventos, filtrando por fecha de creación y por estado de entrega, sin ver nunca eventos de otros clientes.

## ADDED Requirements

### Requirement: Listado de eventos del cliente
`GET /notification_events` SHALL devolver HTTP 200 con un array JSON con los eventos del cliente identificado, ordenados por `created_at` descendente. Cada elemento incluye `event_id`, `event_type`, `content`, `delivery_status`, `created_at` y `delivery_date`, con fechas en ISO-8601 UTC. `delivery_date` es la fecha del último intento de entrega, o `null` si no hubo ninguno.

#### Scenario: Cliente con eventos
- **GIVEN** `CLIENT001` tiene los eventos `EVT001`, `EVT002`, `EVT007` y `EVT010`
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con esos 4 eventos, el más reciente primero

#### Scenario: Cliente sin eventos
- **GIVEN** `CLIENT999` no tiene eventos
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT999`
- **THEN** la respuesta es HTTP 200 con `[]`

### Requirement: Aislamiento por cliente
El listado SHALL incluir únicamente eventos cuyo client_id coincide con el del cliente identificado, con cualquier combinación de filtros.

#### Scenario: Eventos de otro cliente excluidos
- **GIVEN** `CLIENT002` tiene el evento `EVT003` en estado `FAILED`
- **WHEN** se hace `GET /notification_events?delivery_status=FAILED` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 y no contiene `EVT003`

### Requirement: Filtro por fecha de creación
Los params opcionales `from` y `to` (ISO-8601 con zona, p.ej. `2024-03-15T10:00:00Z`) SHALL limitar el resultado a eventos con `from <= created_at <= to`. Se pueden usar por separado.

#### Scenario: Rango completo
- **GIVEN** `CLIENT001` tiene eventos creados a las 09:30, 10:15, 15:20 y 18:05 del 2024-03-15 (UTC)
- **WHEN** se hace `GET /notification_events?from=2024-03-15T10:00:00Z&to=2024-03-15T16:00:00Z` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con los eventos de las 10:15 y las 15:20

#### Scenario: Solo from
- **WHEN** se hace `GET /notification_events?from=2024-03-15T15:00:00Z` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con los eventos de las 15:20 y las 18:05

#### Scenario: Límites inclusivos
- **WHEN** se hace `GET /notification_events?from=2024-03-15T09:30:22Z&to=2024-03-15T09:30:22Z` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 y contiene `EVT001`

### Requirement: Filtro por estado de entrega
El param opcional `delivery_status` SHALL limitar el resultado a eventos con ese estado. Los valores válidos son `PENDING`, `RETRYING`, `DELIVERED`, `FAILED` y `NOT_SUBSCRIBED`, y se aceptan sin distinguir mayúsculas.

#### Scenario: Filtrar fallidos
- **GIVEN** `CLIENT003` tiene `EVT005` y `EVT009` en `FAILED` y `EVT006` en `DELIVERED`
- **WHEN** se hace `GET /notification_events?delivery_status=FAILED` con `X-Client-Id: CLIENT003`
- **THEN** la respuesta es HTTP 200 con `EVT005` y `EVT009`

#### Scenario: Combinado con fechas
- **WHEN** se hace `GET /notification_events?delivery_status=delivered&from=2024-03-15T14:00:00Z` con `X-Client-Id: CLIENT003`
- **THEN** la respuesta es HTTP 200 con `EVT006`

### Requirement: Validación de parámetros
Un parámetro inválido SHALL producir HTTP 400 con un mensaje que nombra el parámetro y el problema, sin ejecutar la consulta.

#### Scenario: delivery_status desconocido
- **WHEN** se hace `GET /notification_events?delivery_status=COMPLETED` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que indica que `delivery_status` es inválido y lista los valores permitidos

#### Scenario: Fecha mal formada
- **WHEN** se hace `GET /notification_events?from=15-03-2024` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que indica que `from` no tiene formato ISO-8601

#### Scenario: Rango invertido
- **WHEN** se hace `GET /notification_events?from=2024-03-16T00:00:00Z&to=2024-03-15T00:00:00Z` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que indica que `from` debe ser anterior o igual a `to`
