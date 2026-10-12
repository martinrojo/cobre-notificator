## MODIFIED Requirements

### Requirement: Reintentos con claim por intento
Un evento en `RETRYING` SHALL reintentarse desde `notificator.retry.v1-0`, `-1` y `-2`, a los 60 s, 300 s y 1500 s del intento fallido, hasta 4 intentos en total por generación. Cada reintento reclama el intento n+1 pasando `attempts` de n a n+1 en una sola operación condicional, antes del POST, solo si la `generation` del evento es la del mensaje. Un mensaje de `transactions.events.v1` pertenece a la generación 0, y un pedido de replay con `generation=g`, igual que sus reintentos, a la generación g+1. Los datos de la entrega salen de la fila, no del mensaje.

#### Scenario: Segundo intento
- **GIVEN** `EVT100` está en `RETRYING` con `generation=0` y `attempts=1`
- **WHEN** se consume su mensaje de `notificator.retry.v1-0` y el webhook responde HTTP 200
- **THEN** antes del POST `attempts` pasa a 2, el POST lleva `X-Delivery-Attempt: 2`, y `EVT100` queda en `DELIVERED` con `attempts=2`

#### Scenario: Segundo intento de un replay
- **GIVEN** `EVT003` está en `RETRYING` con `generation=1` y `attempts=1`, reabierto por un pedido de replay con `generation=0`
- **WHEN** se consume el reintento de ese pedido en `notificator.retry.v1-0` y el webhook responde HTTP 200
- **THEN** antes del POST `attempts` pasa a 2 y `EVT003` queda en `DELIVERED` con `generation=1` y `attempts=2`

#### Scenario: attempts nunca disminuye dentro de una generación
- **GIVEN** cualquier secuencia de intentos, duplicados y recuperaciones de `EVT100`
- **WHEN** se observa `(generation, attempts)` en el tiempo
- **THEN** `generation` nunca baja, `attempts` solo vuelve a 1 cuando `generation` sube, y `attempts` nunca supera 4

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

### Requirement: Recuperación de entregas trabadas
Un proceso periódico SHALL reanudar los eventos en `PENDING` o `RETRYING` sin cambios durante más de `delay(attempts) + lease`, donde `delay` es 60, 300 y 1500 s para `attempts` 1, 2 y 3, y 0 para 4. Con `attempts < 4`, publica en `notificator.retry.v1-<attempts-1>` sin modificar `attempts` ni `generation`, con un mensaje de la generación de la fila. Con `attempts = 4`, marca `FAILED`. Ambas escrituras exigen que la `generation` no haya cambiado.

#### Scenario: Crash con el primer intento en curso
- **GIVEN** `EVT100` está en `PENDING` con `generation=0` y `attempts=1` y la instancia que lo reclamó se cayó antes de guardar el resultado
- **WHEN** pasan más de `60 s + lease` desde su última actualización y corre la recuperación
- **THEN** se publica un mensaje de `EVT100` en `notificator.retry.v1-0` con key `CLIENT001` y el formato de `transactions.events.v1`, y el reintento reclama `attempts=2` y entrega

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
