# Spec Delta

## MODIFIED Requirements

### Requirement: Replay de un evento fallido
`POST /notification_events/{notification_event_id}/replay` SHALL publicar un pedido de replay y responder HTTP 202 sin cuerpo si el evento pertenece al cliente identificado y está en `FAILED`, sin importar el valor de `last_replay_at`. El manager no cambia el estado del evento. En los escenarios, `<id de EVTxxx>` es el `notification_event_id` de esa fila.

#### Scenario: Evento fallido propio
- **GIVEN** `EVT003` pertenece a `CLIENT002`, está en `FAILED` y `last_replay_at` es nulo
- **WHEN** se hace `POST /notification_events/<id de EVT003>/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 202 sin cuerpo, se publica un pedido de replay de `EVT003` y el evento sigue en `FAILED`

#### Scenario: Evento que volvió a fallar después de un replay
- **GIVEN** `EVT005` pertenece a `CLIENT003`, está en `FAILED` y `last_replay_at` no es nulo
- **WHEN** se hace `POST /notification_events/<id de EVT005>/replay` con `X-Client-Id: CLIENT003`
- **THEN** la respuesta es HTTP 202 y se publica un pedido de replay de `EVT005`

### Requirement: Replay en curso
Si el evento del cliente está en `PENDING` o `RETRYING` y `last_replay_at` no es nulo, el endpoint SHALL responder HTTP 202 sin cuerpo y sin publicar. Un replay ya tomado por el notificator está en curso y pedirlo de nuevo no tiene efecto.

#### Scenario: Replay tomado, entrega pendiente
- **GIVEN** `EVT003` pertenece a `CLIENT002`, está en `PENDING` y `last_replay_at` no es nulo
- **WHEN** se hace `POST /notification_events/<id de EVT003>/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 202 y no se publica ningún mensaje

#### Scenario: Replay tomado, reintentando
- **GIVEN** `EVT003` pertenece a `CLIENT002`, está en `RETRYING` y `last_replay_at` no es nulo
- **WHEN** se hace `POST /notification_events/<id de EVT003>/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 202 y no se publica ningún mensaje

### Requirement: Replay no permitido
En cualquier otro caso con evento propio, el endpoint SHALL responder HTTP 409 en formato Problem Details sin publicar. Esos casos son `DELIVERED`, `NOT_SUBSCRIBED`, y `PENDING` o `RETRYING` con `last_replay_at` nulo (primera entrega todavía en curso).

#### Scenario: Evento entregado
- **GIVEN** `EVT001` pertenece a `CLIENT001` y está en `DELIVERED`
- **WHEN** se hace `POST /notification_events/<id de EVT001>/replay` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 409 con `Content-Type: application/problem+json`, `status` 409 y un `detail` que indica que el evento no se puede reenviar en su estado actual, y no se publica ningún mensaje

#### Scenario: Evento sin suscripción
- **GIVEN** un evento de `CLIENT001` en `NOT_SUBSCRIBED`
- **WHEN** se hace `POST /notification_events/{notification_event_id}/replay` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 409 y no se publica ningún mensaje

#### Scenario: Primera entrega en curso
- **GIVEN** un evento de `CLIENT001` en `RETRYING` con `last_replay_at` nulo
- **WHEN** se hace `POST /notification_events/{notification_event_id}/replay` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 409 y no se publica ningún mensaje

### Requirement: Aislamiento por cliente en el replay
Si el evento no existe, pertenece a otro cliente, o el valor del path no es un UUID válido, el endpoint SHALL responder HTTP 404 en formato Problem Details, con el mismo `title` y `detail` en todos los casos, sin publicar. El evento se busca por `notification_event_id` y `client_id` en una sola consulta, así que el estado de un evento ajeno nunca influye en la respuesta.

#### Scenario: Evento fallido de otro cliente
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hace `POST /notification_events/<id de EVT003>/replay` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 404 con un `detail` que indica que el evento no existe, y no se publica ningún mensaje

#### Scenario: Evento entregado de otro cliente
- **GIVEN** `EVT004` pertenece a `CLIENT002` y está en `DELIVERED`
- **WHEN** se hace `POST /notification_events/<id de EVT004>/replay` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 404, no 409, con el mismo `title` y `detail` que el escenario anterior

#### Scenario: Evento inexistente
- **GIVEN** no existe ningún evento con `notification_event_id` `0199c7a0-0000-7000-8000-000000000000`
- **WHEN** se hace `POST /notification_events/0199c7a0-0000-7000-8000-000000000000/replay` con `X-Client-Id: CLIENT001`
- **THEN** la respuesta es HTTP 404 con el mismo `title` y `detail` que los escenarios anteriores

#### Scenario: event_id en el path
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hace `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 404 con el mismo `title` y `detail` que los escenarios anteriores, y no se publica ningún mensaje

#### Scenario: Sin cliente identificado
- **WHEN** se hace `POST /notification_events/<id de EVT003>/replay` sin el header `X-Client-Id`
- **THEN** la respuesta es HTTP 401 y no se publica ningún mensaje

### Requirement: Mensaje de pedido de replay
El pedido de replay SHALL publicarse en `notifications.replay-requested.v1` con key igual al `event_id` del evento y un valor JSON con `event_id`, `client_id`, `generation` y `requested_at`, tomados de la fila encontrada por `notification_event_id`. `generation` es la generación del evento que leyó el manager al decidir el replay. `requested_at` es el instante en que el manager recibió el pedido, en ISO-8601 UTC.

#### Scenario: Contenido del mensaje
- **GIVEN** `EVT003` pertenece a `CLIENT002`, está en `FAILED` con `generation=0`
- **WHEN** se hace `POST /notification_events/<id de EVT003>/replay` con `X-Client-Id: CLIENT002` a las `2026-10-10T12:00:00.123Z`
- **THEN** la respuesta es HTTP 202 y en `notifications.replay-requested.v1` hay un mensaje con key `EVT003` y valor `{"event_id":"EVT003","client_id":"CLIENT002","generation":0,"requested_at":"2026-10-10T12:00:00.123Z"}`

#### Scenario: Evento que ya tuvo un replay
- **GIVEN** `EVT005` pertenece a `CLIENT003`, está en `FAILED` con `generation=1`
- **WHEN** se hace `POST /notification_events/<id de EVT005>/replay` con `X-Client-Id: CLIENT003`
- **THEN** la respuesta es HTTP 202 y el mensaje publicado tiene `generation` 1

#### Scenario: La key no reemplaza al client_id del valor
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hace `POST /notification_events/<id de EVT003>/replay` con `X-Client-Id: CLIENT002`
- **THEN** el mensaje publicado tiene key `EVT003` y su valor sigue teniendo `client_id` `CLIENT002`, así que el pedido queda asociado al dueño del evento

#### Scenario: Pedidos repetidos del mismo evento
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** se hacen dos `POST /notification_events/<id de EVT003>/replay` seguidos con `X-Client-Id: CLIENT002` y ambos responden HTTP 202
- **THEN** los dos mensajes tienen key `EVT003` y la misma `generation`, están en la misma partición y en el orden en que se publicaron

#### Scenario: El mensaje no incluye el contenido del evento
- **WHEN** se publica un pedido de replay
- **THEN** el valor del mensaje no incluye `content` ni otros datos del evento además de los cuatro campos

### Requirement: 202 solo con confirmación de Kafka
El endpoint SHALL responder HTTP 202 a un evento `FAILED` solo después de que Kafka confirme la escritura con todas las réplicas en sincronía. Si la publicación falla o no se confirma dentro del timeout configurado, SHALL responder HTTP 503 en formato Problem Details con un header `Retry-After` en segundos. Los pedidos concurrentes pueden generar más de una publicación.

#### Scenario: Kafka caído
- **GIVEN** `EVT003` pertenece a `CLIENT002`, está en `FAILED` y el broker no responde
- **WHEN** se hace `POST /notification_events/<id de EVT003>/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 503 con `Content-Type: application/problem+json` y `Retry-After: 5`, dentro del tiempo máximo de bloqueo más el timeout de publicación configurados

#### Scenario: Reintento del cliente después de un 503
- **GIVEN** un pedido de `EVT003` respondió 503 y el broker volvió a estar disponible
- **WHEN** se repite `POST /notification_events/<id de EVT003>/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 202 y se publica el pedido

#### Scenario: Pedidos concurrentes
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** llegan dos `POST /notification_events/<id de EVT003>/replay` simultáneos con `X-Client-Id: CLIENT002`
- **THEN** ambos responden HTTP 202, y puede haber hasta dos pedidos de replay de `EVT003` publicados
