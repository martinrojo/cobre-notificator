# Spec Delta

## ADDED Requirements

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
Solo las señales de cliente no disponible SHALL contar como fallo del circuito: timeout de conexión o de lectura, error de conexión (rechazada, host sin resolver) y HTTP 429, 502, 503 y 504. Cualquier otra respuesta (2xx, 3xx, 4xx salvo 429, 500, 501, 505 y demás 5xx) SHALL contar como éxito, aunque se reintente. El circuito se abre cuando la tasa de fallo de las últimas llamadas del cliente supera el umbral configurado, con un mínimo de llamadas configurado.

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

#### Scenario: Cliente con pocas llamadas
- **GIVEN** `CLIENT003` hizo menos llamadas que el mínimo configurado, todas con HTTP 503
- **WHEN** se procesa otro evento de `CLIENT003`
- **THEN** el circuito de `CLIENT003` sigue cerrado y se hace el POST

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

## MODIFIED Requirements

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

### Requirement: Observabilidad de la entrega
El notificator SHALL exponer métricas de:
- entregas por resultado y `client_id`;
- duplicados e intentos obsoletos descartados;
- mensajes en el DLT;
- latencia del webhook;
- eventos recuperados;
- estado del circuit breaker por `client_id`, e intentos rechazados por circuito abierto.

Un intento rechazado por el circuito no registra latencia del webhook. SHALL registrar INFO en `DELIVERED`, `FAILED` y en cada cambio de estado del circuito de un cliente, ERROR en intentos que se reintentan (incluidos los rechazados por el circuito) y en el DLT, y DEBUG en descartes. Ningún log ni métrica incluye `content`.

#### Scenario: Métrica por resultado
- **WHEN** `EVT100` de `CLIENT001` queda en `DELIVERED`
- **THEN** `/actuator/prometheus` muestra el contador de entregas con `outcome="delivered"` y `client_id="CLIENT001"` incrementado en 1

#### Scenario: Sin contenido en los logs
- **WHEN** se procesa cualquier evento, en cualquier nivel de log
- **THEN** ninguna línea de log contiene el valor de `content`

#### Scenario: Estado del circuito por cliente
- **GIVEN** el circuito de `CLIENT002` se abre y el de `CLIENT001` sigue cerrado
- **WHEN** se consulta `/actuator/prometheus`
- **THEN** la métrica de estado del circuito muestra abierto para `CLIENT002` y cerrado para `CLIENT001`, y se registró un log INFO del cambio de estado de `CLIENT002` con su `client_id`

#### Scenario: Intento rechazado en las métricas
- **WHEN** el circuito abierto de `CLIENT002` rechaza un intento
- **THEN** el contador de intentos al webhook de `CLIENT002` aumenta en 1 con `status="circuit_open"` y el histograma de latencia del webhook de `CLIENT002` no cambia
