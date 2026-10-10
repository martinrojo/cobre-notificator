# Proposal

## Why

La API self-service tiene que permitir obtener el detalle de una notificación de evento puntual (Task 2 del caso). Hoy el manager solo lista eventos. Además, este endpoint es el paso previo natural al replay, que también opera sobre un evento por id.

## What Changes

- Nuevo endpoint `GET /notification_events/{notification_event_id}` en el manager. Devuelve el evento si pertenece al cliente identificado.
- Responde `404` tanto si el evento no existe como si pertenece a otro cliente, con el mismo cuerpo en ambos casos, para no revelar qué ids existen.
- Usa la misma representación JSON que cada elemento del listado.

Aplicaciones afectadas: **manager**. gateway y notificator no se tocan.

### Fuera de alcance

- Replay (`POST /notification_events/{id}/replay`).
- Campos adicionales de diagnóstico (`attempts`, `last_http_status`, `last_error`, `delivery_url`). Si se quieren exponer, va en un cambio aparte, revisando antes qué información es seguro devolverle al cliente.
- Paginación del listado.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities
- `notification-event-query`: se agrega el requisito de consulta de un evento por id, con aislamiento por cliente y 404 indistinguible.

## Impact

- `manager/`: nuevo use case y su servicio, método de búsqueda por id en el port out y en el repositorio JDBC, nueva excepción de dominio, handler 404 en `ApiExceptionHandler` y nuevo método en `NotificationEventController`.
- Sin cambios en la DB, el compose ni las dependencias.
