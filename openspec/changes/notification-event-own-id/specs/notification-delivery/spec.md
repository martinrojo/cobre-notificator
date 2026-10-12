# Spec Delta

## MODIFIED Requirements

### Requirement: Registro del evento como claim del primer intento
El primer intento SHALL crear la fila del evento en `PENDING` con `attempts=1` antes de llamar al webhook, con un `notification_event_id` nuevo: un UUIDv7 generado por la base que no cambia en reintentos ni replays. La creación es atómica sobre `(client_id, event_id)`: si dos procesos la intentan a la vez, solo uno la crea, y solo ese hace el primer intento de entrega.

#### Scenario: Evento nuevo
- **GIVEN** no existe `EVT100` de `CLIENT001`
- **WHEN** se consume `EVT100` de `CLIENT001` de `transactions.events.v1`
- **THEN** antes del POST existe la fila `EVT100` de `CLIENT001` en `PENDING` con `attempts=1` y un `notification_event_id` UUIDv7, y `GET /notification_events/<ese id>` del manager con `X-Client-Id: CLIENT001` la devuelve

#### Scenario: El id no cambia entre intentos
- **GIVEN** `EVT100` de `CLIENT001` se creó con `notification_event_id` `X`
- **WHEN** el evento se reintenta, falla y después se reenvía con un replay
- **THEN** todos los POST de todos los intentos y generaciones llevan `notification_event_id` `X`

### Requirement: Duplicados del tópico principal
Si al consumir un mensaje de `transactions.events.v1` ya existe una fila con su mismo `client_id` y `event_id`, en cualquier estado, el mensaje SHALL descartarse: no se llama al webhook, la fila no cambia y el offset se confirma. Una fila con el mismo `event_id` y otro `client_id` es otro evento y no cuenta como duplicado.

#### Scenario: Duplicado de un evento entregado
- **GIVEN** `EVT100` de `CLIENT001` está en `DELIVERED`
- **WHEN** llega otra vez `EVT100` de `CLIENT001` a `transactions.events.v1`
- **THEN** no se hace ningún POST, `EVT100` sigue en `DELIVERED` con el mismo `attempts`, y el contador de duplicados aumenta en 1

#### Scenario: Duplicado mientras el original reintenta
- **GIVEN** `EVT100` de `CLIENT001` está en `RETRYING` con `attempts=1` y su reintento espera en `notificator.retry.v1-0`
- **WHEN** llega otra vez `EVT100` de `CLIENT001` a `transactions.events.v1`
- **THEN** no se hace ningún POST, `EVT100` sigue en `RETRYING` con `attempts=1` y existe una sola cadena de reintentos

#### Scenario: Duplicado con otro client_id
- **GIVEN** `EVT100` existe con `client_id=CLIENT001` en `DELIVERED`
- **WHEN** llega `EVT100` a `transactions.events.v1` con `client_id=CLIENT002`, y `CLIENT002` tiene suscripción activa a su `event_type`
- **THEN** se crea una segunda fila `EVT100` de `CLIENT002` con otro `notification_event_id`, el POST va solo al webhook de `CLIENT002` con su propio `content`, la fila de `CLIENT001` no cambia, y el contador de duplicados no aumenta

### Requirement: Petición al webhook
Cada intento SHALL hacer un único `POST` con `Content-Type: application/json`, el cuerpo `{notification_event_id, event_id, event_type, client_id, content, created_at}` y los headers `X-Notification-Event-Id`, `X-Event-Id` y `X-Delivery-Attempt` (el `attempts` reclamado), salvo que el circuit breaker del cliente rechace la llamada, en cuyo caso no se hace ningún POST. Solo se aceptan URLs `https`, salvo que la configuración local habilite `http`. No se siguen redirecciones, y la conexión y la respuesta tienen timeouts configurables.

#### Scenario: Headers del tercer intento
- **GIVEN** `EVT100` de `CLIENT001`, con `notification_event_id` `X`, se reclamó con `attempts=3`
- **WHEN** se hace el POST
- **THEN** la petición lleva `X-Notification-Event-Id: X`, `X-Event-Id: EVT100` y `X-Delivery-Attempt: 3`

#### Scenario: Cuerpo de la petición
- **GIVEN** `EVT100` de `CLIENT001`, con `notification_event_id` `X`
- **WHEN** se hace el POST
- **THEN** el cuerpo tiene `notification_event_id` `X`, `event_id` `EVT100`, `client_id` `CLIENT001`, y el `event_type`, `content` y `created_at` de la fila

#### Scenario: URL no https
- **GIVEN** la suscripción de `CLIENT001` tiene `webhook_url=http://a.example/hook` y `http` no está habilitado
- **WHEN** se procesa un evento de `CLIENT001`
- **THEN** no se hace ningún POST y el evento queda en `FAILED` sin reintentos

#### Scenario: Redirección
- **WHEN** el webhook responde HTTP 302
- **THEN** no se sigue la redirección y el evento queda en `FAILED` con `last_http_status=302`

#### Scenario: Circuito abierto
- **GIVEN** el circuito de `CLIENT002` está abierto
- **WHEN** se procesa un evento de `CLIENT002` con suscripción activa y URL permitida
- **THEN** no se hace ningún POST

## ADDED Requirements

### Requirement: Aislamiento entre eventos con el mismo event_id
Todo reintento, mensaje del DLT, pedido de replay y escritura de la recuperación SHALL actuar solo sobre la fila con el `client_id` y el `event_id` del mensaje o de la fila leída. Una fila de otro cliente con el mismo `event_id` nunca cambia por ellos.

#### Scenario: Reintento con event_id compartido
- **GIVEN** existen `EVT100` de `CLIENT001` en `RETRYING` con `attempts=1` y `EVT100` de `CLIENT002` en `DELIVERED`
- **WHEN** se consume el reintento de `EVT100` de `CLIENT001` de `notificator.retry.v1-0` y el webhook responde HTTP 200
- **THEN** `EVT100` de `CLIENT001` queda en `DELIVERED` con `attempts=2`, y `EVT100` de `CLIENT002` no cambia

#### Scenario: DLT con event_id compartido
- **GIVEN** existen `EVT100` de `CLIENT001` en `RETRYING` con `attempts=4` y `EVT100` de `CLIENT002` en `RETRYING` con `attempts=4`
- **WHEN** se consume de `notificator.dlt.v1` el mensaje de `EVT100` de `CLIENT001`
- **THEN** `EVT100` de `CLIENT001` queda en `FAILED` y `EVT100` de `CLIENT002` sigue en `RETRYING`

#### Scenario: Replay con event_id compartido
- **GIVEN** existen `EVT100` de `CLIENT001` en `FAILED` con `generation=0` y `EVT100` de `CLIENT002` en `FAILED` con `generation=0`
- **WHEN** se consume un pedido de replay `{"event_id":"EVT100","client_id":"CLIENT002","generation":0,...}`
- **THEN** solo `EVT100` de `CLIENT002` pasa a `PENDING` con `generation=1`, el POST va al webhook de `CLIENT002`, y `EVT100` de `CLIENT001` sigue en `FAILED` con `generation=0`
