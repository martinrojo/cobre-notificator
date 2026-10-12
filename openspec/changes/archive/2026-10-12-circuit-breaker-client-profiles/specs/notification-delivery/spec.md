# Spec Delta

## MODIFIED Requirements

### Requirement: Resultados que cuentan como fallo del circuito
Solo las señales de cliente no disponible SHALL contar como fallo del circuito:
- timeout de conexión o de lectura;
- error de conexión (rechazada, host sin resolver);
- HTTP 429, 502, 503 y 504.

Cualquier otra respuesta SHALL contar como éxito, aunque se reintente. Eso incluye 2xx, 3xx, los 4xx salvo 429, y los 5xx salvo 502, 503 y 504 (por ejemplo 500, 501 y 505).

El circuito se abre cuando, entre las llamadas del cliente en la ventana de tiempo de su perfil, se cumplen dos condiciones:
- hubo al menos el mínimo de llamadas del perfil;
- la tasa de fallo supera el umbral.

Las llamadas que quedaron fuera de la ventana no cuentan. Con la config por defecto, la ventana es de 60 s, el mínimo es de 10 llamadas y el umbral es del 50%.

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

## ADDED Requirements

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
