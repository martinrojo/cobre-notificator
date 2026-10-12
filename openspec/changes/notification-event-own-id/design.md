# Design

## Context

- `notification_event` tiene `PRIMARY KEY (event_id)`. El notificator identifica la fila solo por `event_id` en todas sus sentencias: `INSERT` del claim, `UPDATE` con fencing `(generation, attempts)` y `SELECT` por id. La excepción es el claim del replay, que además filtra por `client_id`.
- Los mensajes de retry y DLT conservan el valor de origen (un evento de transacción o un pedido de replay), y los dos traen `event_id` y `client_id`. La recuperación lee la fila y publica un valor con formato de transacción. Así, en cada punto donde el notificator toca una fila, ya tiene el par `(client_id, event_id)`.
- El manager busca por `(event_id, client_id)` y publica el replay con el `event_id` del path.
- El webhook se arma serializando `TransactionEvent`, y ese record es también el modelo del mensaje de Kafka.
- Ninguna aplicación tiene DDL. El esquema cambia en `notificator/db/01-schema.sql`.
- MariaDB 12.3.3 (lo verifiqué en el contenedor): el tipo `UUID` con `DEFAULT UUID_v7()` ordena en orden cronológico, `INSERT ... RETURNING id` devuelve el id generado, y comparar una columna `UUID` con un texto que no es UUID da 0 filas y un warning 1292, sin error.

Motivación: ver proposal.md. Comportamiento: ver los deltas en `specs/`.

## Goals / Non-Goals

**Goals:**
- Una sola identidad interna para la fila del lado del notificator: `(client_id, event_id)`. Un `event_id` sin su `client_id` nunca identifica una fila.
- Ningún cambio de formato ni de key en Kafka.
- Que `(client_id, created_at)` más el PK sirva de índice para la paginación por keyset que viene después.

**Non-Goals:**
- Que el notificator use `id` para operar. Solo lo genera, lo lee y lo envía.
- Que el manager acepte `event_id` en el path, ni siquiera como alternativa.

## Decisions

### 1. `id UUID DEFAULT UUID_v7()` como PK, generado por la base

```sql
CREATE TABLE notification_event (
    id         UUID        NOT NULL DEFAULT UUID_v7(),
    event_id   VARCHAR(64) NOT NULL,
    client_id  VARCHAR(64) NOT NULL,
    ...
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_event_client_event UNIQUE (client_id, event_id),
    ...
);
```

- **Por qué la base:** Java 25 no trae UUIDv7 en la librería estándar. Las alternativas son una dependencia nueva (fuera del stack, habría que justificarla) o unas 15 líneas propias con su test. `UUID_v7()` no suma código y genera los ids en un solo reloj, así que dentro de una instancia de DB son monótonos. El seed `03-` no cambia: el `DEFAULT` llena el `id`.
- **Por qué `UUID` y no `BINARY(16)`:** ocupa 16 bytes igual, se lee y se escribe como texto canónico desde JDBC (`getString`), y es legible en un `SELECT`. `BINARY(16)` obligaría a convertir en cada adapter.
- **Por qué `UNIQUE (client_id, event_id)` y no `(event_id, client_id)`:** el prefijo `client_id` coincide con el filtro de todas las consultas del manager. El orden de columnas da igual para la unicidad.
- **Índices existentes:** `idx_event_client_created` e `idx_event_client_status_created` se mantienen. Como InnoDB agrega el PK al final de cada índice secundario, en la práctica pasan a ser `(client_id, created_at, id)`: es justo lo que necesita el keyset `(created_at, id)` del cambio de paginación, y cada entrada pesa 16 bytes de PK en vez de hasta 64.

### 2. El notificator identifica la fila por `(client_id, event_id)`

Todas las sentencias que hoy filtran `event_id = :eventId` pasan a filtrar `client_id = :clientId AND event_id = :eventId`: el `CURRENT` del fencing, el `SELECT` por id y el claim del replay, que ya tenía `client_id`. Los métodos del port `NotificationEventRepository` que reciben `eventId` suman `clientId`. Los callers ya lo tienen: `DeliveryRef`, `TransactionEvent`, `ReplayRequest` y la fila en la recuperación.

Alternativa descartada: operar por `id` después del primer intento. Exigiría agregar `id` al valor de los mensajes de retry y DLT, que hoy conservan el valor de origen, y eso cambia `event-topics`. Además, el primer intento igual necesitaría `(client_id, event_id)`. Con dos claves en juego habría más casos que probar, sin ningún beneficio.

### 3. `insertClaim` devuelve el id

```sql
INSERT INTO notification_event (event_id, client_id, ...) VALUES (...) RETURNING id
```

El port pasa de `boolean insertClaim(TransactionEvent)` a `Optional<String> insertClaim(TransactionEvent)`: vacío si `DuplicateKeyException`, igual que hoy el `false`. Con JdbcClient queda `.query(String.class).single()`, que traduce el duplicado a `DuplicateKeyException` del mismo modo.

Alternativa descartada: hacer un `SELECT` después del `INSERT`. Es un round-trip más para un dato que la base ya devuelve.

### 4. El webhook recibe el id aparte del `TransactionEvent`

`TransactionEvent` sigue siendo el mensaje de Kafka y no gana un campo que el productor no manda. El cuerpo del webhook pasa a ser un record propio del adapter HTTP: `WebhookPayload(notificationEventId, eventId, eventType, clientId, content, createdAt)`. El port pasa a `post(String url, String notificationEventId, TransactionEvent event, int attempt)`.

`DeliveryService.deliver` recibe el id junto al evento:
- `first`: el id sale de `insertClaim`.
- `retry` y `replay`: ya leen la fila con `findById`. `NotificationEvent` gana el campo `id`, y de ahí sale.

Header nuevo: `X-Notification-Event-Id`. `X-Event-Id` no cambia (ver spec `notification-delivery`).

### 5. El manager busca por `id` y valida el formato en el adapter

`FIND_BY_ID` pasa a `WHERE id = :id AND client_id = :clientId`. El adapter JDBC del manager devuelve `Optional.empty()` sin consultar si el valor no tiene 36 caracteres o `UUID.fromString` lo rechaza (`UUID.fromString` sola acepta formas cortas como `1-1-1-1-1`). Si es válido, consulta con `UUID.fromString(value).toString()`, así que las mayúsculas también funcionan. Así se mantiene el 404 indistinguible del spec.

- **Por qué no confiar en la base:** devuelve 0 filas, pero con un warning por request y dependiendo de un comportamiento de conversión implícita. Validar en el borde de confianza es una línea y no depende del motor.
- **Por qué no un 400:** distinguir "formato inválido" de "no existe" no expone nada de otros clientes, pero el spec actual exige el mismo 404 para todo id que no corresponde a un evento propio, y mantenerlo evita un caso más en el contrato.

`NotificationEvent` del manager gana `id`. `NotificationEventResponse` gana `notificationEventId`, que se serializa como `notification_event_id` por la estrategia `SNAKE_CASE` ya configurada. El replay publica `ReplayRequested(event.eventId(), clientId, generation, requestedAt)`, con el `event_id` tomado de la fila y no del path. Las excepciones de dominio (`NotificationEventNotFoundException`, `ReplayNotAllowedException`) siguen recibiendo el valor del path.

### 6. Logs

- Los logs del notificator conservan `event_id` y `client_id`, que juntos identifican la fila.
- Se agrega `notification_event_id` solo donde ya hay una fila o un id a mano, sin lecturas extra:
  - notificator: "Event delivered", "Event failed" y "Replay started".
  - manager: "Replay requested", "Replay not allowed" y "Found notification event". En el manager, el campo `event_id` de esos logs pasa a llevar el `event_id` de la fila, y el valor del path se loguea como `notification_event_id`.
- No hay métricas nuevas: ningún tag lleva ids, por cardinalidad.

### Seguridad (OWASP)

- **A01 Broken Access Control:** el id del path es un UUID no secuencial, pero la protección sigue siendo `client_id` en el mismo `WHERE` y no la imposibilidad de adivinar el id. El cambio además cierra una falla real: hoy un productor que reusa el `event_id` de otro cliente provoca que esa notificación se descarte (spec `notification-delivery`, escenario "Duplicado con otro client_id").
- **A03 Injection:** el id del path solo entra como parámetro bind, y además se valida su formato antes de la consulta.

## Risks / Trade-offs

- [Un `event_id` de cliente distinto deja de ser duplicado] → Es el comportamiento buscado. Lo cubren el escenario "Duplicado con otro client_id" del delta y los de aislamiento en retry, DLT y replay.
- [Algún `UPDATE` queda filtrando solo por `event_id` y modifica la fila de otro cliente] → Se mitiga desde tres frentes: el fragmento `CURRENT` es compartido por todas las sentencias de fencing y se cambia una sola vez, hay un test de repositorio con dos clientes y el mismo `event_id` por cada método de escritura, y los escenarios del delta tienen tests.
- [Clientes de la API que guardaron `event_id` para el path reciben 404] → Es un cambio que rompe compatibilidad (**BREAKING**) y está declarado. Hoy no hay consumidores fuera del entorno local. El listado devuelve los dos ids para que el cliente haga la correlación.
- [`UUID_v7()` depende de MariaDB 11.7 o posterior] → El compose fija `mariadb:12.3.3`. Si se cambia de motor, la generación pasa a Java (decisión 1, alternativa).
- [Monotonía de UUIDv7 entre instancias de base] → Hay una sola base. El orden por `id` solo sirve de desempate en el keyset, que solo necesita que el orden sea total, no que sea monótono.

## Migration Plan

1. Los scripts de `notificator/db/` solo corren con un volumen nuevo, así que en local se recrea con `docker compose down -v && docker compose up --build`.
2. No hay rollback de datos: volver atrás es `git revert` más recrear el volumen.
3. En un entorno con datos, la migración sería `ALTER TABLE ... ADD COLUMN id UUID NOT NULL DEFAULT UUID_v7()`, seguido de cambio de PK y `UNIQUE`, en una ventana, y desplegar el notificator antes que el manager. No se implementa aquí (proposal, Fuera de alcance).
