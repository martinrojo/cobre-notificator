# Spec Delta

## Purpose

Entrega cada evento de `transactions.events.v1` al webhook del cliente suscrito. Persiste el estado final y garantiza que duplicados, reintentos y procesamiento concurrente del mismo `event_id` no generen entregas en paralelo, no retrocedan un estado final y no dejen eventos trabados.

## ADDED Requirements

### Requirement: Mensaje del tópico principal
Cada mensaje de `transactions.events.v1` SHALL ser un JSON con `event_id`, `client_id` y `event_type` (no vacíos, hasta 64 caracteres), `content` (string) y `created_at` (instante ISO-8601). Un mensaje inválido SHALL ir a `notificator.dlt.v1` sin reintentos, sin crear fila y sin llamar a ningún webhook.

#### Scenario: Mensaje válido
- **GIVEN** no existe `EVT100`
- **WHEN** llega a `transactions.events.v1` `{"event_id":"EVT100","client_id":"CLIENT001","event_type":"credit_deposit","content":"...","created_at":"2026-10-11T10:00:00Z"}`
- **THEN** el evento se procesa y queda una fila `EVT100` con `client_id=CLIENT001` y `created_at=2026-10-11 10:00:00.000`

#### Scenario: Mensaje sin event_id
- **WHEN** llega un mensaje sin `event_id` o con JSON mal formado
- **THEN** el mensaje se publica en `notificator.dlt.v1`, no se crea ninguna fila, no se hace ningún POST y se registra un log ERROR sin el `content`

### Requirement: Registro del evento como claim del primer intento
El primer intento SHALL crear la fila del evento en `PENDING` con `attempts=1` antes de llamar al webhook. La creación es atómica sobre `event_id`: si dos procesos la intentan a la vez, solo uno la crea, y solo ese hace el primer intento de entrega.

#### Scenario: Evento nuevo
- **GIVEN** no existe `EVT100`
- **WHEN** se consume `EVT100` de `transactions.events.v1`
- **THEN** antes del POST existe la fila `EVT100` en `PENDING` con `attempts=1`, y `GET /notification_events/EVT100` del manager la devuelve

### Requirement: Duplicados del tópico principal
Si al consumir un mensaje de `transactions.events.v1` ya existe una fila con su `event_id`, en cualquier estado, el mensaje SHALL descartarse: no se llama al webhook, la fila no cambia y el offset se confirma.

#### Scenario: Duplicado de un evento entregado
- **GIVEN** `EVT100` está en `DELIVERED`
- **WHEN** llega otra vez `EVT100` a `transactions.events.v1`
- **THEN** no se hace ningún POST, `EVT100` sigue en `DELIVERED` con el mismo `attempts`, y el contador de duplicados aumenta en 1

#### Scenario: Duplicado mientras el original reintenta
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=1` y su reintento espera en `notificator.retry.v1-0`
- **WHEN** llega otra vez `EVT100` a `transactions.events.v1`
- **THEN** no se hace ningún POST, `EVT100` sigue en `RETRYING` con `attempts=1` y existe una sola cadena de reintentos

#### Scenario: Duplicado con otro client_id
- **GIVEN** `EVT100` existe con `client_id=CLIENT001`
- **WHEN** llega `EVT100` a `transactions.events.v1` con `client_id=CLIENT002`
- **THEN** no se hace ningún POST a ningún webhook, la fila sigue con `client_id=CLIENT001` y el contenido no se entrega a `CLIENT002`

### Requirement: Validación de suscripción en cada intento
Antes de cada POST, el notificator SHALL buscar la suscripción activa por el `client_id` y el `event_type` guardados en la fila del evento, y usar su `webhook_url` vigente. Si no hay una suscripción activa, el evento SHALL pasar a `NOT_SUBSCRIBED` sin POST. Nunca se usa la suscripción de otro cliente.

#### Scenario: Cliente sin suscripción al tipo
- **GIVEN** `CLIENT001` no tiene suscripción activa a `credit_deposit`, y `CLIENT002` sí la tiene
- **WHEN** se consume `EVT100` de `CLIENT001` con `event_type=credit_deposit`
- **THEN** no se hace ningún POST, en particular ninguno al webhook de `CLIENT002`, y `EVT100` queda en `NOT_SUBSCRIBED`

#### Scenario: Suscripción desactivada entre intentos
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=1` y la suscripción de `CLIENT001` a su tipo pasa a `active=false`
- **WHEN** se consume el reintento de `notificator.retry.v1-0`
- **THEN** no se hace ningún POST y `EVT100` queda en `NOT_SUBSCRIBED`

#### Scenario: URL cambiada entre intentos
- **GIVEN** `EVT100` está en `RETRYING` y la `webhook_url` de su suscripción cambió de `https://a.example/hook` a `https://b.example/hook`
- **WHEN** se consume el reintento
- **THEN** el POST va a `https://b.example/hook` y `delivery_url` queda en ese valor

### Requirement: Petición al webhook
Cada intento SHALL hacer un único `POST` con `Content-Type: application/json`, el cuerpo `{event_id, event_type, client_id, content, created_at}` y los headers `X-Event-Id` y `X-Delivery-Attempt` (el `attempts` reclamado). Solo se aceptan URLs `https`, salvo que la configuración local habilite `http`. No se siguen redirecciones, y la conexión y la respuesta tienen timeouts configurables.

#### Scenario: Headers del tercer intento
- **GIVEN** `EVT100` se reclamó con `attempts=3`
- **WHEN** se hace el POST
- **THEN** la petición lleva `X-Event-Id: EVT100` y `X-Delivery-Attempt: 3`

#### Scenario: URL no https
- **GIVEN** la suscripción de `CLIENT001` tiene `webhook_url=http://a.example/hook` y `http` no está habilitado
- **WHEN** se procesa un evento de `CLIENT001`
- **THEN** no se hace ningún POST y el evento queda en `FAILED` sin reintentos

#### Scenario: Redirección
- **WHEN** el webhook responde HTTP 302
- **THEN** no se sigue la redirección y el evento queda en `FAILED` con `last_http_status=302`

### Requirement: Resultado de la entrega
El resultado del POST SHALL determinar la transición:
- 2xx → `DELIVERED`.
- Timeout, error de conexión, 408, 429 o 5xx → `RETRYING` y reintento.
- Cualquier otro código → `FAILED`, sin reintento.

Cada intento SHALL guardar `last_attempt_at`, `delivery_url`, `last_http_status` (cuando hubo respuesta) y `last_error` (hasta 500 caracteres, sin el `content`).

#### Scenario: Entrega exitosa
- **GIVEN** `EVT100` reclamado con `attempts=1`
- **WHEN** el webhook responde HTTP 204
- **THEN** `EVT100` queda en `DELIVERED` con `attempts=1` y `last_http_status=204`, y se registra un log INFO

#### Scenario: Error reintentable
- **GIVEN** `EVT100` reclamado con `attempts=1`
- **WHEN** el webhook responde HTTP 503
- **THEN** `EVT100` queda en `RETRYING` con `attempts=1` y `last_http_status=503`, el mensaje se publica en `notificator.retry.v1-0` y se registra un log ERROR

#### Scenario: Timeout
- **WHEN** el webhook no responde dentro del timeout
- **THEN** el evento queda en `RETRYING`, `last_http_status` queda nulo y `last_error` describe el timeout

#### Scenario: Error no reintentable
- **GIVEN** `EVT100` reclamado con `attempts=1`
- **WHEN** el webhook responde HTTP 400
- **THEN** `EVT100` queda en `FAILED` con `last_http_status=400`, no se publica ningún reintento y se registra un log INFO

### Requirement: Reintentos con claim por intento
Un evento en `RETRYING` SHALL reintentarse desde `notificator.retry.v1-0`, `-1` y `-2`, a los 60 s, 300 s y 1500 s del intento fallido, hasta 4 intentos en total. Cada reintento reclama el intento n+1 pasando `attempts` de n a n+1 en una sola operación condicional, antes del POST. Los datos de la entrega salen de la fila, no del mensaje.

#### Scenario: Segundo intento
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=1`
- **WHEN** se consume su mensaje de `notificator.retry.v1-0` y el webhook responde HTTP 200
- **THEN** antes del POST `attempts` pasa a 2, el POST lleva `X-Delivery-Attempt: 2`, y `EVT100` queda en `DELIVERED` con `attempts=2`

#### Scenario: attempts nunca disminuye
- **GIVEN** cualquier secuencia de intentos, duplicados y recuperaciones de `EVT100` dentro del flujo principal
- **WHEN** se observa `attempts` en el tiempo
- **THEN** su valor nunca baja y nunca supera 4

### Requirement: Intento obsoleto
Un mensaje cuyo intento ya fue reclamado por otro proceso, o cuyo evento ya está en un estado final, SHALL descartarse sin POST y sin alterar la fila. Un proceso que pierde la carrera al guardar un resultado no reintentable o reintentable SHALL descartar ese resultado, sin publicar un reintento.

#### Scenario: Dos mensajes para el mismo intento
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=1` y hay dos mensajes de su reintento en `notificator.retry.v1-0`: el original demorado y uno publicado por la recuperación
- **WHEN** se consumen ambos
- **THEN** solo uno pasa `attempts` a 2 y hace el POST, y el otro se descarta sin POST

#### Scenario: Reintento de un evento ya entregado
- **GIVEN** `EVT100` está en `DELIVERED`
- **WHEN** llega un mensaje de `EVT100` a `notificator.retry.v1-1`
- **THEN** no se hace ningún POST y `EVT100` sigue en `DELIVERED`

#### Scenario: Resultado tardío de un intento superado
- **GIVEN** el intento 1 de `EVT100` sigue en curso en un proceso, y otro proceso ya reclamó el intento 2
- **WHEN** el intento 1 recibe HTTP 503
- **THEN** la fila no cambia por ese resultado y no se publica ningún reintento por el intento 1

### Requirement: El éxito siempre gana
Una respuesta 2xx de cualquier intento SHALL dejar el evento en `DELIVERED` mientras esté en `PENDING` o `RETRYING`, aunque otro proceso haya reclamado un intento posterior. Un evento en `DELIVERED`, `FAILED` o `NOT_SUBSCRIBED` SHALL quedar sin cambios en el flujo principal.

#### Scenario: Éxito tardío del intento anterior
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=2`, reclamado por otro proceso
- **WHEN** el intento 1, todavía en curso, recibe HTTP 200
- **THEN** `EVT100` queda en `DELIVERED`, y el intento 2 al terminar no cambia ese estado

### Requirement: Agotamiento de reintentos
Si el cuarto intento falla con un error reintentable, el mensaje SHALL pasar a `notificator.dlt.v1`, y su consumo SHALL dejar el evento en `FAILED` solo si sigue en `PENDING` o `RETRYING` con el `attempts` de ese intento. Un mensaje del DLT sin fila, o con la fila en otro estado o con otro `attempts`, se descarta.

#### Scenario: Cuarto intento fallido
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=4`
- **WHEN** el POST del cuarto intento recibe HTTP 500
- **THEN** el mensaje se publica en `notificator.dlt.v1`, `EVT100` queda en `FAILED` con `attempts=4`, y se registra un log ERROR

#### Scenario: DLT de un evento ya entregado
- **GIVEN** `EVT100` está en `DELIVERED`
- **WHEN** se consume un mensaje de `EVT100` de `notificator.dlt.v1`
- **THEN** `EVT100` sigue en `DELIVERED`

### Requirement: Indisponibilidad transitoria de la base
Un error transitorio de la base de datos (sin conexión, timeout, deadlock) SHALL reintentar el mismo mensaje en el mismo offset con backoff, sin publicarlo en un tópico de retry ni en el DLT. La partición no avanza hasta que el mensaje se procesa.

#### Scenario: Base caída al recibir un evento
- **GIVEN** MariaDB no responde
- **WHEN** se consume `EVT100` de `transactions.events.v1`
- **THEN** no se publica nada en `notificator.retry.v1-*` ni en `notificator.dlt.v1`, el offset no se confirma, y cuando MariaDB vuelve se crea `EVT100` y se entrega

### Requirement: Recuperación de entregas trabadas
Un proceso periódico SHALL reanudar los eventos en `PENDING` o `RETRYING` sin cambios durante más de `delay(attempts) + lease`, donde `delay` es 60, 300 y 1500 s para `attempts` 1, 2 y 3, y 0 para 4. Con `attempts < 4`, publica en `notificator.retry.v1-<attempts-1>` sin modificar `attempts`. Con `attempts = 4`, marca `FAILED`.

#### Scenario: Crash con el primer intento en curso
- **GIVEN** `EVT100` está en `PENDING` con `attempts=1` y la instancia que lo reclamó se cayó antes de guardar el resultado
- **WHEN** pasan más de `60 s + lease` desde su última actualización y corre la recuperación
- **THEN** se publica un mensaje de `EVT100` en `notificator.retry.v1-0` con key `CLIENT001`, y el reintento reclama `attempts=2` y entrega

#### Scenario: Reintento perdido
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=2` y su mensaje nunca llegó a `notificator.retry.v1-1`
- **WHEN** pasan más de `300 s + lease` y corre la recuperación
- **THEN** se publica `EVT100` en `notificator.retry.v1-1` y `attempts` sigue en 2

#### Scenario: Último intento sin resultado
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=4` sin cambios durante más de `lease`
- **WHEN** corre la recuperación
- **THEN** `EVT100` queda en `FAILED` y no se publica ningún mensaje

#### Scenario: Base caída entre el 2xx y el registro del resultado
- **GIVEN** el webhook respondió 2xx al intento k de `EVT100` y MariaDB cayó antes de marcarlo `DELIVERED`
- **WHEN** MariaDB vuelve, el mensaje se reprocesa y pasan más de `delay(k) + lease`
- **THEN** el reprocesamiento descarta el mensaje y la recuperación sigue como si el intento k no tuviera resultado: con `k < 4` el webhook recibe un POST repetido con el mismo `X-Event-Id` y `EVT100` termina en `DELIVERED`; con `k = 4` `EVT100` queda en `FAILED` aunque el cliente lo recibió

#### Scenario: Evento que avanza normalmente
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=3`, actualizado hace 600 s
- **WHEN** corre la recuperación
- **THEN** `EVT100` no se toca, porque su umbral es `1500 s + lease`

#### Scenario: Varias instancias de recuperación
- **GIVEN** dos instancias del notificator detectan a la vez `EVT100` trabado
- **WHEN** ambas corren la recuperación
- **THEN** se publica un solo mensaje de `EVT100`

### Requirement: Observabilidad de la entrega
El notificator SHALL exponer métricas de:
- entregas por resultado y `client_id`;
- duplicados e intentos obsoletos descartados;
- mensajes en el DLT;
- latencia del webhook;
- eventos recuperados.

SHALL registrar INFO en `DELIVERED` y `FAILED`, ERROR en intentos que se reintentan y en el DLT, y DEBUG en descartes. Ningún log ni métrica incluye `content`.

#### Scenario: Métrica por resultado
- **WHEN** `EVT100` de `CLIENT001` queda en `DELIVERED`
- **THEN** `/actuator/prometheus` muestra el contador de entregas con `outcome="delivered"` y `client_id="CLIENT001"` incrementado en 1

#### Scenario: Sin contenido en los logs
- **WHEN** se procesa cualquier evento, en cualquier nivel de log
- **THEN** ninguna línea de log contiene el valor de `content`
