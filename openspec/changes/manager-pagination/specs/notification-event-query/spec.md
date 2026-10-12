# Spec Delta

## MODIFIED Requirements

### Requirement: Listado de eventos del cliente
`GET /notification_events` SHALL devolver HTTP 200 con un objeto JSON `{ "data": [...], "next_cursor": ... }`. `data` contiene una página de eventos del cliente identificado, ordenados por `created_at` descendente y, a igual `created_at`, por `notification_event_id` descendente. Cada elemento incluye `notification_event_id`, `event_id`, `event_type`, `content`, `delivery_status`, `created_at` y `delivery_date`, con fechas en ISO-8601 UTC. `delivery_date` es la fecha del último intento de entrega, o `null` si no hubo ninguno.

#### Scenario: Cliente con eventos
- **GIVEN** `CLIENT001` tiene los eventos `EVT001`, `EVT002`, `EVT007` y `EVT010`
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con esos 4 eventos en `data`, el más reciente primero, cada uno con su `notification_event_id` y su `event_id`, y `next_cursor` `null`

#### Scenario: Cliente sin eventos
- **GIVEN** `CLIENT999` no tiene eventos
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT999`
- **THEN** la respuesta es HTTP 200 con `data` `[]` y `next_cursor` `null`

#### Scenario: Mismo event_id en dos clientes
- **GIVEN** existen `EVT100` de `CLIENT001` y `EVT100` de `CLIENT002`
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 y `data` contiene un solo `EVT100`, el de `CLIENT001`, con su propio `notification_event_id`

#### Scenario: Empate en created_at
- **GIVEN** `CLIENT001` tiene dos eventos con el mismo `created_at`
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT001`
- **THEN** los dos aparecen ordenados por `notification_event_id` descendente, y el orden es el mismo en cada request

## ADDED Requirements

### Requirement: Tamaño de página
El param opcional `size` SHALL limitar la cantidad de elementos de `data`. Sin `size`, la página tiene el tamaño por defecto (20). El tamaño máximo (100) y el tamaño por defecto se configuran en las properties del manager.

#### Scenario: Tamaño por defecto
- **GIVEN** `CLIENT001` tiene 25 eventos
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con los 20 eventos más recientes en `data` y un `next_cursor` no nulo

#### Scenario: Tamaño explícito
- **GIVEN** `CLIENT001` tiene 25 eventos
- **WHEN** se hace `GET /notification_events?size=5` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con los 5 eventos más recientes en `data` y un `next_cursor` no nulo

#### Scenario: Tamaño máximo aceptado
- **WHEN** se hace `GET /notification_events?size=100` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200

### Requirement: Paginación por cursor
Si hay más eventos después de la página devuelta, `next_cursor` SHALL ser un token opaco, y `null` si no los hay. Al repetir el request con `cursor=<next_cursor>` y los mismos filtros, se devuelve la página siguiente. Ningún evento aparece en dos páginas ni se omite, aunque se inserten eventos nuevos entre requests. `size` puede cambiar entre páginas.

#### Scenario: Recorrido completo
- **GIVEN** `CLIENT001` tiene 5 eventos
- **WHEN** se hace `GET /notification_events?size=2` y luego se sigue cada `next_cursor` con `X-Client-Id: CLIENT001`
- **THEN** se obtienen 3 páginas con 2, 2 y 1 eventos, los 5 sin repetir y en orden, y la última página tiene `next_cursor` `null`

#### Scenario: Página exacta
- **GIVEN** `CLIENT001` tiene 4 eventos
- **WHEN** se hace `GET /notification_events?size=4` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con los 4 eventos y `next_cursor` `null`

#### Scenario: Evento nuevo durante el recorrido
- **GIVEN** `CLIENT001` pidió la primera página con `size=2` y recibió un `next_cursor`
- **WHEN** se inserta un evento nuevo más reciente y se pide la página siguiente con ese `next_cursor`
- **THEN** la página siguiente no repite eventos de la primera ni incluye el evento nuevo

#### Scenario: Cambio de size entre páginas
- **GIVEN** `CLIENT001` recibió un `next_cursor` en una página con `size=2`
- **WHEN** se pide `GET /notification_events?size=10&cursor=<next_cursor>` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con hasta 10 eventos que siguen al último de la página anterior

#### Scenario: Cursor con filtros
- **GIVEN** `CLIENT003` pidió `GET /notification_events?delivery_status=FAILED&size=1` y recibió un `next_cursor`
- **WHEN** se pide `GET /notification_events?delivery_status=FAILED&size=1&cursor=<next_cursor>` con `X-Client-Id: CLIENT003`
- **THEN** la respuesta es HTTP 200 y `data` contiene solo eventos `FAILED` de `CLIENT003`

### Requirement: Validación de size
Un `size` mayor al máximo configurado, menor o igual a 0, o no numérico SHALL producir HTTP 400 con un mensaje que nombra `size` y el rango permitido, sin ejecutar la consulta.

#### Scenario: size mayor al máximo
- **WHEN** se hace `GET /notification_events?size=101` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que indica que `size` debe estar entre 1 y 100

#### Scenario: size cero
- **WHEN** se hace `GET /notification_events?size=0` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que indica que `size` debe estar entre 1 y 100

#### Scenario: size no numérico
- **WHEN** se hace `GET /notification_events?size=abc` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que nombra `size`

### Requirement: Validación del cursor
Un `cursor` que no se puede decodificar, que se generó con otros valores de `from`, `to` o `delivery_status`, o que se generó para otro cliente SHALL producir HTTP 400 con un mensaje que nombra `cursor`, sin ejecutar la consulta. Los filtros se comparan por valor: fechas equivalentes en distinta zona horaria y `delivery_status` en distinta capitalización se consideran iguales.

#### Scenario: Cursor mal formado
- **WHEN** se hace `GET /notification_events?cursor=not-a-cursor` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que indica que `cursor` es inválido

#### Scenario: Filtros distintos a los del cursor
- **GIVEN** `CLIENT001` recibió un `next_cursor` de `GET /notification_events?delivery_status=FAILED`
- **WHEN** se pide `GET /notification_events?cursor=<next_cursor>` sin `delivery_status` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que indica que `cursor` no corresponde a los filtros actuales

#### Scenario: Filtros equivalentes
- **GIVEN** `CLIENT001` recibió un `next_cursor` de `GET /notification_events?from=2024-03-15T10:00:00Z&delivery_status=FAILED`
- **WHEN** se pide `GET /notification_events?from=2024-03-15T07:00:00-03:00&delivery_status=failed&cursor=<next_cursor>` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200

#### Scenario: Cursor de otro cliente
- **GIVEN** `CLIENT002` recibió un `next_cursor`
- **WHEN** se pide `GET /notification_events?cursor=<next_cursor de CLIENT002>` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 400 con un mensaje que indica que `cursor` es inválido, y no se devuelve ningún evento de `CLIENT002`
