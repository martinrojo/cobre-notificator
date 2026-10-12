## MODIFIED Requirements

### Requirement: Mensaje de pedido de replay
El pedido de replay SHALL publicarse en `notifications.replay-requested.v1` con key igual al `event_id` del evento y un valor JSON con `event_id`, `client_id`, `generation` y `requested_at`. `generation` es la generación del evento que leyó el manager al decidir el replay. `requested_at` es el instante en que el manager recibió el pedido, en ISO-8601 UTC.

#### Scenario: Contenido del mensaje
- **GIVEN** `EVT003` pertenece a `CLIENT002`, está en `FAILED` con `generation=0`
- **WHEN** se hace `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002` a las `2026-10-10T12:00:00.123Z`
- **THEN** la respuesta es HTTP 202 y en `notifications.replay-requested.v1` hay un mensaje con key `EVT003` y valor `{"event_id":"EVT003","client_id":"CLIENT002","generation":0,"requested_at":"2026-10-10T12:00:00.123Z"}`

#### Scenario: Evento que ya tuvo un replay
- **GIVEN** `EVT005` pertenece a `CLIENT003`, está en `FAILED` con `generation=1`
- **WHEN** se hace `POST /notification_events/EVT005/replay` con `X-Client-Id: CLIENT003`
- **THEN** la respuesta es HTTP 202 y el mensaje publicado tiene `generation` 1

#### Scenario: La key no reemplaza al client_id del valor
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hace `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002`
- **THEN** el mensaje publicado tiene key `EVT003` y su valor sigue teniendo `client_id` `CLIENT002`, así que el pedido queda asociado al dueño del evento

#### Scenario: Pedidos repetidos del mismo evento
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hacen dos `POST /notification_events/EVT003/replay` seguidos con `X-Client-Id: CLIENT002` y ambos responden HTTP 202
- **THEN** los dos mensajes tienen key `EVT003` y la misma `generation`, están en la misma partición y en el orden en que se publicaron

#### Scenario: El mensaje no incluye el contenido del evento
- **WHEN** se publica un pedido de replay
- **THEN** el valor del mensaje no incluye `content` ni otros datos del evento además de los cuatro campos

## ADDED Requirements

### Requirement: Claim del replay en el notificator
Al consumir un pedido de replay, el notificator SHALL reabrir el evento en una sola operación condicional: pasa a `PENDING` con `generation` incrementada en 1, `attempts=1` y `last_replay_at` igual a `requested_at`, solo si el evento sigue en `FAILED`, pertenece al `client_id` del mensaje y su `generation` es la del mensaje. Solo si gana ese claim hace el POST, como intento 1 de la nueva generación.

#### Scenario: Replay de un evento fallido
- **GIVEN** `EVT003` de `CLIENT002` está en `FAILED` con `generation=0` y `attempts=4`, con suscripción activa
- **WHEN** se consume un pedido de replay `{"event_id":"EVT003","client_id":"CLIENT002","generation":0,...}` y el webhook responde HTTP 200
- **THEN** antes del POST `EVT003` pasa a `PENDING` con `generation=1`, `attempts=1` y `last_replay_at` no nulo, el POST lleva `X-Delivery-Attempt: 1`, y `EVT003` queda en `DELIVERED`

#### Scenario: Los datos de la entrega salen de la fila
- **GIVEN** `EVT003` de `CLIENT002` está en `FAILED` con `generation=0`
- **WHEN** se consume su pedido de replay
- **THEN** el POST va al webhook de la suscripción vigente de `CLIENT002` para el `event_type` de la fila, con el `content` de la fila

#### Scenario: Replay que vuelve a fallar
- **GIVEN** `EVT003` fue reabierto por un replay y está en `PENDING` con `generation=1` y `attempts=1`
- **WHEN** el webhook responde HTTP 503
- **THEN** `EVT003` queda en `RETRYING` y se reintenta desde `notificator.retry.v1-0` como intento 2 de la generación 1, con el mismo calendario y tope de 4 intentos que el flujo principal

### Requirement: Pedido de replay duplicado u obsoleto
Un pedido de replay que no gana el claim SHALL descartarse sin POST y sin alterar la fila. Eso incluye: evento que no está en `FAILED`, `generation` distinta de la del evento, evento inexistente, y `client_id` distinto del dueño del evento.

#### Scenario: Dos pedidos de la misma generación
- **GIVEN** `EVT003` de `CLIENT002` está en `FAILED` con `generation=0` y hay dos pedidos de replay con `generation=0` en el tópico
- **WHEN** se consumen ambos
- **THEN** el primero lleva `EVT003` a `generation=1` y hace el POST, y el segundo se descarta sin POST

#### Scenario: Pedido tardío de una generación anterior
- **GIVEN** `EVT003` de `CLIENT002` está en `FAILED` con `generation=1`, después de un replay que volvió a fallar
- **WHEN** se consume un pedido de replay de `EVT003` con `generation=0`
- **THEN** se descarta sin POST y `EVT003` sigue en `FAILED` con `generation=1`

#### Scenario: Evento entregado
- **GIVEN** `EVT001` de `CLIENT001` está en `DELIVERED` con `generation=0`
- **WHEN** se consume un pedido de replay de `EVT001` con `generation=0`
- **THEN** se descarta sin POST y `EVT001` sigue en `DELIVERED`

#### Scenario: client_id que no es el dueño
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED` con `generation=0`
- **WHEN** se consume un pedido de replay `{"event_id":"EVT003","client_id":"CLIENT001","generation":0,...}`
- **THEN** se descarta sin POST, `EVT003` sigue en `FAILED` y ningún webhook de `CLIENT001` ni de `CLIENT002` recibe el evento

#### Scenario: Evento inexistente
- **GIVEN** no existe ningún evento `EVT999`
- **WHEN** se consume un pedido de replay de `EVT999`
- **THEN** se descarta sin POST y no se crea ninguna fila

### Requirement: Pedido de replay inválido
Un pedido de replay sin `event_id`, `client_id` o `generation`, con `generation` negativa, o que no es JSON válido, SHALL ir a `notificator.dlt.v1` sin tocar ninguna fila.

#### Scenario: Pedido sin generation
- **WHEN** se consume `{"event_id":"EVT003","client_id":"CLIENT002","requested_at":"2026-10-10T12:00:00.123Z"}` de `notifications.replay-requested.v1`
- **THEN** el mensaje pasa a `notificator.dlt.v1`, se registra como inválido y `EVT003` no cambia

### Requirement: Observabilidad del replay
El notificator SHALL exponer una métrica de pedidos de replay por resultado (tomado o descartado) y registrar INFO cuando toma un replay y DEBUG cuando lo descarta, con `event_id`, `client_id` y `generation` como campos estructurados, sin `content`.

#### Scenario: Replay tomado
- **WHEN** el notificator toma el pedido de replay de `EVT003` de `CLIENT002`
- **THEN** `/actuator/prometheus` muestra el contador de replays con `outcome="started"` incrementado en 1 y hay un log INFO con `event_id=EVT003`
