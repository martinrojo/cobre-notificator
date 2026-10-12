## MODIFIED Requirements

### Requirement: Mensaje de pedido de replay
El pedido de replay SHALL publicarse en `notifications.replay-requested.v1` con key igual al `event_id` del evento y un valor JSON con `event_id`, `client_id` y `requested_at`. `requested_at` es el instante en que el manager recibió el pedido, en ISO-8601 UTC.

#### Scenario: Contenido del mensaje
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hace `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002` a las `2026-10-10T12:00:00.123Z`
- **THEN** la respuesta es HTTP 202 y en `notifications.replay-requested.v1` hay un mensaje con key `EVT003` y valor `{"event_id":"EVT003","client_id":"CLIENT002","requested_at":"2026-10-10T12:00:00.123Z"}`

#### Scenario: La key no reemplaza al client_id del valor
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hace `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002`
- **THEN** el mensaje publicado tiene key `EVT003` y su valor sigue teniendo `client_id` `CLIENT002`, así que el pedido queda asociado al dueño del evento

#### Scenario: Pedidos repetidos del mismo evento
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hacen dos `POST /notification_events/EVT003/replay` seguidos con `X-Client-Id: CLIENT002` y ambos responden HTTP 202
- **THEN** los dos mensajes tienen key `EVT003`, están en la misma partición y en el orden en que se publicaron

#### Scenario: El mensaje no incluye el contenido del evento
- **WHEN** se publica un pedido de replay
- **THEN** el valor del mensaje no incluye `content` ni otros datos del evento además de los tres campos
