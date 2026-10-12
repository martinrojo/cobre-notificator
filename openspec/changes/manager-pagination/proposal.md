# Proposal

## Why

`GET /notification_events` devuelve hoy todos los eventos del cliente en una sola respuesta, sin límite. Para un cliente con mucho volumen la respuesta crece sin cota (memoria del manager, latencia, carga en MariaDB). El caso pide un listado paginado con tamaño de página máximo configurable, y eso todavía no existe.

## What Changes

- **BREAKING**: `GET /notification_events` responde con un envelope `{ "data": [...], "next_cursor": "..." | null }` en lugar de un array JSON pelado.
- Paginación por cursor (keyset) con orden estable `created_at` descendente y desempate por `notification_event_id` descendente.
- Nuevo param `size`: por defecto 20 y máximo 100, configurables por properties. Si está fuera de rango o no es numérico, responde 400.
- Nuevo param `cursor`: token opaco que devuelve la página anterior en `next_cursor`. Responde 400 si está mal formado, si se generó con otros filtros (`from`, `to`, `delivery_status`) o si pertenece a otro cliente.
- `size` puede cambiar entre páginas, pero los filtros no.

Aplicaciones afectadas: solo el **manager**. El gateway y el notificator no cambian.

Fuera de alcance:
- Total de resultados (`COUNT`), página anterior y salto a la página N.
- Cambios de esquema o de índices: los índices existentes ya cubren el orden con desempate.
- Firma del cursor (HMAC). Ver design.md.

## Capabilities

### New Capabilities

_Ninguna._

### Modified Capabilities

- `notification-event-query`: el listado pasa a responder un envelope con orden estable y se agregan requisitos de paginación por cursor y de validación de `size` y `cursor`.

## Impact

- Manager: controller y DTO de request y response del listado, caso de uso y servicio de consulta, puerto y adaptador JDBC (`ORDER BY`, predicado keyset, `LIMIT`), mensajes de `InvalidParameterException` y nuevas properties `manager.query.default-page-size` y `manager.query.max-page-size`.
- Consumidores de la API: el cuerpo del listado cambia de forma, y el ejemplo de `curl` del README pasa a mostrar `data` y `next_cursor`.
- Base de datos: sin cambios.
