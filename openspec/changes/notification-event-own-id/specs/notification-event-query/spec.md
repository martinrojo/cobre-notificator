# Spec Delta

## MODIFIED Requirements

### Requirement: Listado de eventos del cliente
`GET /notification_events` SHALL devolver HTTP 200 con un array JSON con los eventos del cliente identificado, ordenados por `created_at` descendente. Cada elemento incluye `notification_event_id`, `event_id`, `event_type`, `content`, `delivery_status`, `created_at` y `delivery_date`, con fechas en ISO-8601 UTC. `delivery_date` es la fecha del último intento de entrega, o `null` si no hubo ninguno.

#### Scenario: Cliente con eventos
- **GIVEN** `CLIENT001` tiene los eventos `EVT001`, `EVT002`, `EVT007` y `EVT010`
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con esos 4 eventos, el más reciente primero, cada uno con su `notification_event_id` y su `event_id`

#### Scenario: Cliente sin eventos
- **GIVEN** `CLIENT999` no tiene eventos
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT999`
- **THEN** la respuesta es HTTP 200 con `[]`

#### Scenario: Mismo event_id en dos clientes
- **GIVEN** existen `EVT100` de `CLIENT001` y `EVT100` de `CLIENT002`
- **WHEN** se hace `GET /notification_events` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 y contiene un solo `EVT100`, el de `CLIENT001`, con su propio `notification_event_id`

### Requirement: Detalle de un evento del cliente
`GET /notification_events/{notification_event_id}` SHALL devolver HTTP 200 con el evento cuyo `notification_event_id` coincide, si pertenece al cliente identificado, con la misma representación que un elemento del listado. En los escenarios, `<id de EVTxxx>` es el `notification_event_id` de esa fila.

#### Scenario: Evento propio
- **GIVEN** `EVT001` pertenece a `CLIENT001` y está en `DELIVERED`
- **WHEN** se hace `GET /notification_events/<id de EVT001>` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con `notification_event_id` `<id de EVT001>`, `event_id` `EVT001`, `delivery_status` `DELIVERED` y `created_at` `2024-03-15T09:30:22Z`

#### Scenario: Evento fallido propio
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hace `GET /notification_events/<id de EVT003>` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 200 con `event_id` `EVT003` y `delivery_status` `FAILED`

### Requirement: 404 indistinguible para eventos ajenos o inexistentes
Si no existe un evento con ese `notification_event_id`, si pertenece a otro cliente, o si el valor no es un UUID válido, el endpoint SHALL responder HTTP 404 con el mismo `title` y `detail` en todos los casos, de modo que la respuesta no revele si el id existe. Un `event_id` en el path no identifica al evento.

#### Scenario: Evento de otro cliente
- **GIVEN** `EVT003` pertenece a `CLIENT002`
- **WHEN** se hace `GET /notification_events/<id de EVT003>` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 404 con un mensaje que indica que el evento no existe

#### Scenario: Evento inexistente
- **GIVEN** no existe ningún evento con `notification_event_id` `0199c7a0-0000-7000-8000-000000000000`
- **WHEN** se hace `GET /notification_events/0199c7a0-0000-7000-8000-000000000000` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 404 con el mismo `title` y `detail` que el escenario anterior

#### Scenario: event_id en el path
- **GIVEN** `EVT001` pertenece a `CLIENT001`
- **WHEN** se hace `GET /notification_events/EVT001` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 404 con el mismo `title` y `detail` que los escenarios anteriores

#### Scenario: Sin cliente identificado
- **WHEN** se hace `GET /notification_events/<id de EVT001>` sin el header `X-Client-Id`
- **THEN** la respuesta es HTTP 401
