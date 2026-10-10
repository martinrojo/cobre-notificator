# Spec Delta

## ADDED Requirements

### Requirement: Detalle de un evento del cliente
`GET /notification_events/{notification_event_id}` SHALL devolver HTTP 200 con el evento cuyo id coincide, si pertenece al cliente identificado, con la misma representación que un elemento del listado (`event_id`, `event_type`, `content`, `delivery_status`, `created_at`, `delivery_date`).

#### Scenario: Evento propio
- **GIVEN** `EVT001` pertenece a `CLIENT001` y está en `DELIVERED`
- **WHEN** se hace `GET /notification_events/EVT001` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 200 con `event_id` `EVT001`, `delivery_status` `DELIVERED` y `created_at` `2024-03-15T09:30:22Z`

#### Scenario: Evento fallido propio
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hace `GET /notification_events/EVT003` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 200 con `event_id` `EVT003` y `delivery_status` `FAILED`

### Requirement: 404 indistinguible para eventos ajenos o inexistentes
Si el evento no existe o pertenece a otro cliente, el endpoint SHALL responder HTTP 404 con el mismo `title` y `detail` en ambos casos, de modo que la respuesta no revele si el id existe.

#### Scenario: Evento de otro cliente
- **GIVEN** `EVT003` pertenece a `CLIENT002`
- **WHEN** se hace `GET /notification_events/EVT003` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 404 con un mensaje que indica que el evento no existe

#### Scenario: Evento inexistente
- **GIVEN** no existe ningún evento `EVT999`
- **WHEN** se hace `GET /notification_events/EVT999` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 404 con el mismo `title` y `detail` que el escenario anterior

#### Scenario: Sin cliente identificado
- **WHEN** se hace `GET /notification_events/EVT001` sin el header `X-Client-Id`
- **THEN** la respuesta es HTTP 401
