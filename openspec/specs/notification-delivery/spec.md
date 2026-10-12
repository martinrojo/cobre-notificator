# notification-delivery Specification

## Purpose

Entrega cada evento de `transactions.events.v1` al webhook del cliente suscrito. Persiste el estado final y garantiza que duplicados, reintentos y procesamiento concurrente del mismo `event_id` no generen entregas en paralelo, no retrocedan un estado final y no dejen eventos trabados.

## Requirements

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
Cada intento SHALL hacer un único `POST` con `Content-Type: application/json`, el cuerpo `{event_id, event_type, client_id, content, created_at}` y los headers `X-Event-Id` y `X-Delivery-Attempt` (el `attempts` reclamado), salvo que el circuit breaker del cliente rechace la llamada, en cuyo caso no se hace ningún POST. Solo se aceptan URLs `https`, salvo que la configuración local habilite `http`. No se siguen redirecciones, y la conexión y la respuesta tienen timeouts configurables.

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

#### Scenario: Circuito abierto
- **GIVEN** el circuito de `CLIENT002` está abierto
- **WHEN** se procesa un evento de `CLIENT002` con suscripción activa y URL permitida
- **THEN** no se hace ningún POST

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
Un evento en `RETRYING` SHALL reintentarse desde `notificator.retry.v1-0`, `-1` y `-2`, a los 60 s, 300 s y 1500 s del intento fallido, hasta 4 intentos en total por generación. Cada reintento reclama el intento n+1 pasando `attempts` de n a n+1 en una sola operación condicional, antes del POST, solo si la `generation` del evento es la del mensaje.

#### Scenario: Segundo intento
- **GIVEN** `EVT100` está en `RETRYING` con `generation=0` y `attempts=1`
- **WHEN** se consume su mensaje de `notificator.retry.v1-0` y el webhook responde HTTP 200
- **THEN** antes del POST `attempts` pasa a 2, el POST lleva `X-Delivery-Attempt: 2`, y `EVT100` queda en `DELIVERED` con `attempts=2`

#### Scenario: attempts nunca disminuye dentro de una generación
- **GIVEN** cualquier secuencia de intentos, duplicados y recuperaciones de `EVT100`
- **WHEN** se observa `(generation, attempts)` en el tiempo
- **THEN** `generation` nunca baja, `attempts` solo vuelve a 1 cuando `generation` sube, y `attempts` nunca supera 4

### Requirement: Generación de un intento
Un mensaje de `transactions.events.v1` SHALL pertenecer a la generación 0, y un pedido de replay con `generation=g`, igual que sus reintentos, a la generación g+1. Los datos de la entrega SHALL salir de la fila, no del mensaje.

#### Scenario: Segundo intento de un replay
- **GIVEN** `EVT003` está en `RETRYING` con `generation=1` y `attempts=1`, reabierto por un pedido de replay con `generation=0`
- **WHEN** se consume el reintento de ese pedido en `notificator.retry.v1-0` y el webhook responde HTTP 200
- **THEN** antes del POST `attempts` pasa a 2 y `EVT003` queda en `DELIVERED` con `generation=1` y `attempts=2`

### Requirement: Intento obsoleto
Un mensaje cuyo intento ya fue reclamado por otro proceso, cuyo evento ya está en un estado final, o cuya generación no es la del evento, SHALL descartarse sin POST y sin alterar la fila. Un proceso que pierde la carrera al guardar un resultado, o cuya generación ya no es la del evento, SHALL descartar ese resultado, sin publicar un reintento.

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

#### Scenario: Reintento de una generación anterior
- **GIVEN** `EVT003` está en `PENDING` con `generation=1` y `attempts=1` después de un replay, y en `notificator.retry.v1-0` queda un mensaje de la generación 0 que espera `attempts=1`
- **WHEN** se consume ese mensaje
- **THEN** se descarta sin POST, aunque `attempts` coincida, y `EVT003` sigue en `generation=1` con `attempts=1`

### Requirement: El éxito siempre gana
Una respuesta 2xx de cualquier intento SHALL dejar el evento en `DELIVERED` mientras esté en `PENDING` o `RETRYING` en la misma generación, aunque otro proceso haya reclamado un intento posterior. Un evento en `DELIVERED`, `FAILED` o `NOT_SUBSCRIBED` SHALL quedar sin cambios fuera del claim de un replay, y un 2xx de una generación anterior no cambia el evento.

#### Scenario: Éxito tardío del intento anterior
- **GIVEN** `EVT100` está en `RETRYING` con `attempts=2`, reclamado por otro proceso
- **WHEN** el intento 1, todavía en curso, recibe HTTP 200
- **THEN** `EVT100` queda en `DELIVERED`, y el intento 2 al terminar no cambia ese estado

#### Scenario: Éxito tardío de una generación anterior
- **GIVEN** un intento de la generación 0 de `EVT003` sigue en curso, y un replay ya llevó `EVT003` a `generation=1`
- **WHEN** ese intento recibe HTTP 200
- **THEN** `EVT003` no cambia por ese resultado y la generación 1 sigue su curso

### Requirement: Agotamiento de reintentos
Si el cuarto intento de una generación falla con un error reintentable, el mensaje SHALL pasar a `notificator.dlt.v1`, y su consumo SHALL dejar el evento en `FAILED` solo si sigue en `PENDING` o `RETRYING` con la `generation` del mensaje y el `attempts` de ese intento. Un mensaje del DLT sin fila, o con la fila en otro estado, otra generación u otro `attempts`, se descarta.

#### Scenario: Cuarto intento fallido
- **GIVEN** `EVT100` está en `RETRYING` con `generation=0` y `attempts=4`
- **WHEN** el POST del cuarto intento recibe HTTP 500
- **THEN** el mensaje se publica en `notificator.dlt.v1`, `EVT100` queda en `FAILED` con `attempts=4`, y se registra un log ERROR

#### Scenario: DLT de un evento ya entregado
- **GIVEN** `EVT100` está en `DELIVERED`
- **WHEN** se consume un mensaje de `EVT100` de `notificator.dlt.v1`
- **THEN** `EVT100` sigue en `DELIVERED`

#### Scenario: DLT de una generación anterior
- **GIVEN** `EVT003` está en `RETRYING` con `generation=1` y `attempts=4`
- **WHEN** se consume un mensaje de `EVT003` de la generación 0 de `notificator.dlt.v1` por su intento 4
- **THEN** `EVT003` sigue en `RETRYING` con `generation=1`

### Requirement: Indisponibilidad transitoria de la base
Un error transitorio de la base de datos (sin conexión, timeout, deadlock) SHALL reintentar el mismo mensaje en el mismo offset con backoff, sin publicarlo en un tópico de retry ni en el DLT. La partición no avanza hasta que el mensaje se procesa.

#### Scenario: Base caída al recibir un evento
- **GIVEN** MariaDB no responde
- **WHEN** se consume `EVT100` de `transactions.events.v1`
- **THEN** no se publica nada en `notificator.retry.v1-*` ni en `notificator.dlt.v1`, el offset no se confirma, y cuando MariaDB vuelve se crea `EVT100` y se entrega

### Requirement: Recuperación de entregas trabadas
Un proceso periódico SHALL reanudar los eventos en `PENDING` o `RETRYING` sin cambios durante más de `delay(attempts) + lease`, donde `delay` es 60, 300 y 1500 s para `attempts` 1, 2 y 3, y 0 para 4. Con `attempts < 4`, publica en `notificator.retry.v1-<attempts-1>` sin modificar `attempts` ni `generation`, con un mensaje de la generación de la fila. Con `attempts = 4`, marca `FAILED`. Ambas escrituras exigen que la `generation` no haya cambiado.

#### Scenario: Crash con el primer intento en curso
- **GIVEN** `EVT100` está en `PENDING` con `generation=0` y `attempts=1` y la instancia que lo reclamó se cayó antes de guardar el resultado
- **WHEN** pasan más de `60 s + lease` desde su última actualización y corre la recuperación
- **THEN** se publica un mensaje de `EVT100` en `notificator.retry.v1-0` con key `EVT100` y el formato de `transactions.events.v1`, y el reintento reclama `attempts=2` y entrega

#### Scenario: Crash con el primer intento de un replay en curso
- **GIVEN** `EVT003` de `CLIENT002` está en `PENDING` con `generation=1` y `attempts=1`, y la instancia que tomó el replay se cayó antes del POST
- **WHEN** pasan más de `60 s + lease` y corre la recuperación
- **THEN** se publica en `notificator.retry.v1-0` un mensaje con key `EVT003` y valor de pedido de replay con `generation` 0, y el reintento reclama `attempts=2` en la generación 1 y entrega

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

#### Scenario: Replay entre la lectura y la escritura de la recuperación
- **GIVEN** la recuperación leyó `EVT003` en `RETRYING` con `generation=0` y `attempts=4`, y antes de escribir el evento terminó en `FAILED` y un replay lo llevó a `generation=1`
- **WHEN** la recuperación intenta marcarlo `FAILED`
- **THEN** la escritura no afecta la fila y la generación 1 sigue su curso

### Requirement: Observabilidad de la entrega
El notificator SHALL exponer métricas de:
- entregas por resultado y `client_id`;
- duplicados e intentos obsoletos descartados;
- mensajes en el DLT;
- latencia del webhook;
- eventos recuperados;
- estado del circuit breaker por `client_id`, e intentos rechazados por circuito abierto.

Un intento rechazado por el circuito no registra latencia del webhook. Ninguna métrica incluye `content`.

#### Scenario: Métrica por resultado
- **WHEN** `EVT100` de `CLIENT001` queda en `DELIVERED`
- **THEN** `/actuator/prometheus` muestra el contador de entregas con `outcome="delivered"` y `client_id="CLIENT001"` incrementado en 1

#### Scenario: Estado del circuito por cliente
- **GIVEN** el circuito de `CLIENT002` se abre y el de `CLIENT001` sigue cerrado
- **WHEN** se consulta `/actuator/prometheus`
- **THEN** la métrica de estado del circuito muestra abierto para `CLIENT002` y cerrado para `CLIENT001`, y se registró un log INFO del cambio de estado de `CLIENT002` con su `client_id`

#### Scenario: Intento rechazado en las métricas
- **WHEN** el circuito abierto de `CLIENT002` rechaza un intento
- **THEN** el contador de intentos al webhook de `CLIENT002` aumenta en 1 con `status="circuit_open"` y el histograma de latencia del webhook de `CLIENT002` no cambia

### Requirement: Logs de la entrega
El notificator SHALL registrar:
- INFO en `DELIVERED`, `FAILED` y en cada cambio de estado del circuito de un cliente;
- ERROR en intentos que se reintentan (incluidos los rechazados por el circuito) y en el DLT;
- DEBUG en descartes.

Ningún log incluye `content`.

#### Scenario: Sin contenido en los logs
- **WHEN** se procesa cualquier evento, en cualquier nivel de log
- **THEN** ninguna línea de log contiene el valor de `content`

### Requirement: Circuit breaker por cliente
El notificator SHALL mantener un circuit breaker independiente por `client_id` sobre la llamada al webhook. Las llamadas, fallos, aperturas y llamadas de prueba de un cliente nunca cuentan para el circuito de otro. El estado vive en memoria de cada instancia y no se comparte entre instancias.

#### Scenario: Circuito abierto de un cliente no afecta a otro
- **GIVEN** el circuito de `CLIENT002` está abierto y el de `CLIENT001` está cerrado, y ambos clientes comparten partición de `transactions.events.v1`
- **WHEN** se consumen `EVT200` de `CLIENT002` y luego `EVT100` de `CLIENT001`
- **THEN** no se hace ningún POST para `EVT200`, que queda en `RETRYING`, y se hace el POST de `EVT100`, que con HTTP 200 queda en `DELIVERED`

#### Scenario: Aislamiento en un tópico de retry
- **GIVEN** el circuito de `CLIENT002` está abierto y el de `CLIENT001` está cerrado, y en la misma partición de `notificator.retry.v1-0` hay, ya vencidos, varios reintentos de `CLIENT002` seguidos del reintento de `EVT100` de `CLIENT001`
- **WHEN** se consumen esos mensajes
- **THEN** los reintentos de `CLIENT002` no hacen POST y pasan a `notificator.retry.v1-1`, y el reintento de `EVT100` hace el POST sin esperar ningún timeout de `CLIENT002`, y con HTTP 200 queda en `DELIVERED`

#### Scenario: Fallos de un cliente no abren el circuito de otro
- **GIVEN** el webhook de `CLIENT002` responde HTTP 503 a todo y el de `CLIENT001` responde HTTP 200
- **WHEN** se procesan suficientes eventos de `CLIENT002` como para abrir su circuito, intercalados con eventos de `CLIENT001`
- **THEN** el circuito de `CLIENT002` queda abierto, el de `CLIENT001` sigue cerrado y todos los eventos de `CLIENT001` quedan en `DELIVERED`

### Requirement: Resultados que cuentan como fallo del circuito
Solo las señales de cliente no disponible SHALL contar como fallo del circuito:
- timeout de conexión o de lectura;
- error de conexión (rechazada, host sin resolver);
- HTTP 429, 502, 503 y 504.

Cualquier otra respuesta SHALL contar como éxito, aunque se reintente. Eso incluye 2xx, 3xx, los 4xx salvo 429, y los 5xx salvo 502, 503 y 504 (por ejemplo 500, 501 y 505).

#### Scenario: HTTP 500 no abre el circuito
- **GIVEN** el circuito de `CLIENT001` está cerrado
- **WHEN** el webhook de `CLIENT001` responde HTTP 500 a todos los eventos
- **THEN** cada evento queda en `RETRYING` con `last_http_status=500` y sigue los reintentos, el circuito de `CLIENT001` sigue cerrado y cada reintento hace POST

#### Scenario: HTTP 503 abre el circuito
- **GIVEN** el circuito de `CLIENT002` está cerrado
- **WHEN** el webhook de `CLIENT002` responde HTTP 503 en suficientes llamadas para superar el umbral
- **THEN** el circuito de `CLIENT002` pasa a abierto y el siguiente evento de `CLIENT002` no hace POST

#### Scenario: HTTP 429 abre el circuito
- **GIVEN** el circuito de `CLIENT002` está cerrado
- **WHEN** el webhook de `CLIENT002` responde HTTP 429 en suficientes llamadas para superar el umbral
- **THEN** cada evento queda en `RETRYING` con `last_http_status=429` y el circuito de `CLIENT002` pasa a abierto

#### Scenario: HTTP 408 no abre el circuito
- **GIVEN** el circuito de `CLIENT001` está cerrado
- **WHEN** el webhook de `CLIENT001` responde HTTP 408 a todos los eventos
- **THEN** cada evento queda en `RETRYING` y el circuito de `CLIENT001` sigue cerrado

#### Scenario: Conexión rechazada abre el circuito
- **GIVEN** el circuito de `CLIENT002` está cerrado
- **WHEN** el host del webhook de `CLIENT002` rechaza la conexión en suficientes llamadas para superar el umbral
- **THEN** el circuito de `CLIENT002` pasa a abierto

#### Scenario: Errores permanentes no abren el circuito
- **GIVEN** el circuito de `CLIENT001` está cerrado
- **WHEN** el webhook de `CLIENT001` responde HTTP 400 a todos los eventos
- **THEN** cada evento queda en `FAILED` con `last_http_status=400` y el circuito de `CLIENT001` sigue cerrado

#### Scenario: Timeouts abren el circuito
- **GIVEN** el circuito de `CLIENT002` está cerrado
- **WHEN** el webhook de `CLIENT002` no responde dentro del timeout en suficientes llamadas para superar el umbral
- **THEN** el circuito de `CLIENT002` pasa a abierto y el siguiente evento de `CLIENT002` no hace POST

### Requirement: Apertura del circuito por ventana de tiempo
El circuito SHALL abrirse cuando, entre las llamadas del cliente en la ventana de tiempo de su perfil, se cumplen dos condiciones:
- hubo al menos el mínimo de llamadas del perfil;
- la tasa de fallo supera el umbral.

Las llamadas que quedaron fuera de la ventana no cuentan. Con la config por defecto, la ventana es de 60 s, el mínimo es de 10 llamadas y el umbral es del 50%.

#### Scenario: Cliente con pocas llamadas
- **GIVEN** `CLIENT003` usa la config por defecto y en los últimos 60 s hizo 9 llamadas, todas con HTTP 503
- **WHEN** se procesa otro evento de `CLIENT003`
- **THEN** el circuito de `CLIENT003` sigue cerrado, se hace el POST y, con HTTP 503, el evento queda en `RETRYING` con `last_http_status=503`

#### Scenario: Fallos fuera de la ventana no cuentan
- **GIVEN** `CLIENT003` usa la config por defecto e hizo 9 llamadas con HTTP 503 hace más de 60 s
- **WHEN** se procesan 9 eventos más de `CLIENT003`, que también responden HTTP 503
- **THEN** el circuito de `CLIENT003` sigue cerrado, porque en la ventana solo hay 9 llamadas, y cada evento hace POST y queda en `RETRYING`

#### Scenario: Un blip breve de un cliente de alto volumen no abre el circuito
- **GIVEN** `CLIENT001` usa la config por defecto y en los últimos 60 s hizo 600 llamadas con HTTP 200
- **WHEN** las siguientes 20 llamadas de `CLIENT001` responden HTTP 503
- **THEN** el circuito de `CLIENT001` sigue cerrado, porque la tasa de fallo en la ventana queda por debajo del 50%

### Requirement: Intento rechazado por circuito abierto
Si el circuito del cliente rechaza la llamada, el intento reclamado SHALL terminar sin POST como un error reintentable: el evento pasa a `RETRYING` con `last_error = "Circuit open"`, `last_http_status` nulo y `delivery_url` de la suscripción vigente, y sigue los reintentos normales. El intento cuenta para el límite de 4 por generación. El mensaje no se retiene en la partición.

#### Scenario: Primer intento con el circuito abierto
- **GIVEN** el circuito de `CLIENT002` está abierto y no existe `EVT200`
- **WHEN** se consume `EVT200` de `CLIENT002` de `transactions.events.v1`
- **THEN** no se hace ningún POST, `EVT200` queda en `RETRYING` con `attempts=1`, `last_http_status` nulo y `last_error="Circuit open"`, se publica en `notificator.retry.v1-0`, se registra un log ERROR y el offset se confirma sin esperar al circuito

#### Scenario: Cuarto intento con el circuito abierto
- **GIVEN** `EVT200` de `CLIENT002` está en `RETRYING` con `generation=0` y `attempts=3`, y el circuito de `CLIENT002` está abierto
- **WHEN** se consume su reintento de `notificator.retry.v1-2`
- **THEN** `attempts` pasa a 4 sin POST, el mensaje va a `notificator.dlt.v1` y `EVT200` queda en `FAILED` con `last_error="Circuit open"`

#### Scenario: Replay con el circuito abierto
- **GIVEN** `EVT003` de `CLIENT002` está en `FAILED` y el circuito de `CLIENT002` está abierto
- **WHEN** se consume un pedido de replay de `EVT003`
- **THEN** `EVT003` pasa a la nueva generación con `attempts=1`, no se hace ningún POST y queda en `RETRYING` con `last_error="Circuit open"`

### Requirement: Prueba del webhook tras la espera
Pasado el tiempo de espera en abierto, el circuito SHALL permitir un número configurado de llamadas reales de prueba: si su tasa de fallo queda bajo el umbral el circuito se cierra, y si no vuelve a abrirse. Las llamadas de ese cliente que excedan las de prueba se rechazan como en el circuito abierto. El tiempo de espera SHALL ser menor que el primer delay de reintento (60 s).

#### Scenario: El cliente se recupera
- **GIVEN** el circuito de `CLIENT002` está abierto, pasó el tiempo de espera y su webhook vuelve a responder HTTP 200
- **WHEN** llegan los reintentos de `notificator.retry.v1-0` de eventos de `CLIENT002`
- **THEN** las llamadas de prueba hacen POST y sus eventos quedan en `DELIVERED`, el circuito se cierra y los eventos siguientes de `CLIENT002` hacen POST

#### Scenario: El cliente sigue caído
- **GIVEN** el circuito de `CLIENT002` está abierto, pasó el tiempo de espera y su webhook sigue respondiendo HTTP 503
- **WHEN** llegan las llamadas de prueba
- **THEN** esas llamadas hacen POST, sus eventos quedan en `RETRYING` con `last_http_status=503`, y el circuito vuelve a abrirse

#### Scenario: Cada ola de reintentos llega a probar el webhook
- **GIVEN** el circuito de `CLIENT002` se abrió al fallar el primer intento de `EVT200`
- **WHEN** se consume el reintento de `EVT200` de `notificator.retry.v1-0`, 60 s después
- **THEN** el circuito de `CLIENT002` ya no está abierto, y ese reintento hace POST si hay cupo de prueba disponible

### Requirement: Perfiles de circuito por cliente
La configuración del notificator SHALL permitir asignar a cada `client_id` un perfil de circuito con nombre. Un perfil solo cambia la ventana y el mínimo de llamadas; todo lo demás es igual que en el default. Un cliente sin perfil usa el default. La asignación se lee al arrancar.

#### Scenario: Cliente con perfil de bajo volumen
- **GIVEN** `CLIENT008` tiene asignado el perfil `bajo-volumen` (ventana de 300 s, mínimo de 5 llamadas) y su circuito está cerrado
- **WHEN** en 4 minutos el webhook de `CLIENT008` responde HTTP 503 a 5 llamadas
- **THEN** el circuito de `CLIENT008` pasa a abierto y el siguiente evento de `CLIENT008` no hace POST y queda en `RETRYING` con `last_error="Circuit open"`

#### Scenario: El mismo patrón con la config por defecto no abre
- **GIVEN** `CLIENT003` no tiene perfil asignado y su circuito está cerrado
- **WHEN** en 4 minutos el webhook de `CLIENT003` responde HTTP 503 a 5 llamadas
- **THEN** el circuito de `CLIENT003` sigue cerrado y cada evento queda en `RETRYING` con `last_http_status=503`

#### Scenario: El perfil de un cliente no afecta a otro
- **GIVEN** `CLIENT008` tiene asignado `bajo-volumen`, `CLIENT001` no tiene perfil, ambos comparten partición y el webhook de `CLIENT008` responde HTTP 503 a todo
- **WHEN** se procesan 5 eventos de `CLIENT008` y 5 de `CLIENT001`, cuyo webhook responde HTTP 200
- **THEN** el circuito de `CLIENT008` queda abierto, el de `CLIENT001` sigue cerrado y los 5 eventos de `CLIENT001` quedan en `DELIVERED`

#### Scenario: El perfil hereda la espera y las pruebas del default
- **GIVEN** `CLIENT008` tiene asignado `bajo-volumen` y su circuito se abrió
- **WHEN** pasan 30 s y llega un evento de `CLIENT008` cuyo webhook responde HTTP 200
- **THEN** se hace el POST de prueba, igual que para un cliente sin perfil, y el evento queda en `DELIVERED`
