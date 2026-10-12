# event-topics Specification

## Purpose

Define los tópicos de Kafka del flujo de notificaciones (principal, reintentos, DLT y replay): sus nombres, particiones y retención, y cómo se aprovisionan de forma reproducible en el entorno local.

## Requirements

### Requirement: Tópicos del flujo de notificaciones
El entorno SHALL tener exactamente estos tópicos del flujo: `transactions.events.v1` con 10 particiones, y `notificator.retry.v1-0`, `notificator.retry.v1-1`, `notificator.retry.v1-2`, `notificator.dlt.v1` y `notifications.replay-requested.v1` con 3 particiones cada uno. El principal lleva todo el tráfico; los demás, solo lo que falla o se reenvía. Los tópicos con el prefijo `notificator.` son internos del notificator. `notifications.replay-requested.v1` es el contrato entre el manager, que produce, y el notificator, que consume.

#### Scenario: Tópicos creados al levantar el entorno
- **GIVEN** un entorno sin volumen de Kafka
- **WHEN** se ejecuta `docker compose up` y `kafka-init` termina con exit code 0
- **THEN** `kafka-topics.sh --describe` lista los 6 tópicos, `transactions.events.v1` con `PartitionCount: 10` y el resto con `PartitionCount: 3`

#### Scenario: Tópicos de retry por índice
- **GIVEN** reintentos con delays de 60 s, 300 s y 1500 s
- **WHEN** se listan los tópicos de retry
- **THEN** existe exactamente un tópico por reintento, con los sufijos `-retry-0`, `-retry-1` y `-retry-2` en ese orden, y ninguno se nombra por su delay

#### Scenario: El tópico de replay anterior ya no existe
- **GIVEN** un entorno sin volumen de Kafka
- **WHEN** `kafka-init` termina con exit code 0
- **THEN** `notificator.replay.v1` no existe

### Requirement: Retención por tópico
Cada tópico SHALL tener retención explícita por tiempo (`cleanup.policy=delete`): `transactions.events.v1` 7 días (604800000 ms), los `notificator.retry.v1-*` 1 día (86400000 ms), `notificator.dlt.v1` 14 días (1209600000 ms) y `notifications.replay-requested.v1` 7 días (604800000 ms).

#### Scenario: Retención del tópico principal
- **GIVEN** los tópicos aprovisionados
- **WHEN** se consulta la configuración de `transactions.events.v1`
- **THEN** `retention.ms=604800000`

#### Scenario: La retención de los retries cubre el delay más largo
- **GIVEN** el tópico `notificator.retry.v1-2`, con delay de 1500 s
- **WHEN** se consulta su configuración
- **THEN** `retention.ms=86400000`, mayor que el delay, así que ningún mensaje expira antes de reintentarse

#### Scenario: Retención del DLT
- **GIVEN** los tópicos aprovisionados
- **WHEN** se consulta la configuración de `notificator.dlt.v1`
- **THEN** `retention.ms=1209600000`

#### Scenario: Retención del tópico de replay
- **GIVEN** los tópicos aprovisionados
- **WHEN** se consulta la configuración de `notifications.replay-requested.v1`
- **THEN** `retention.ms=604800000`

### Requirement: Aprovisionamiento idempotente
El aprovisionamiento de tópicos SHALL poder re-ejecutarse sin errores. En cada ejecución crea los tópicos que falten y vuelve a aplicar la retención declarada a los existentes.

#### Scenario: Segunda ejecución
- **GIVEN** los 6 tópicos ya existen
- **WHEN** `kafka-init` se ejecuta de nuevo
- **THEN** termina con exit code 0 y los 6 tópicos conservan sus particiones y su retención declarada

#### Scenario: Se corrige el drift de retención
- **GIVEN** `transactions.events.v1` existe con `retention.ms=3600000`, cambiado a mano
- **WHEN** se ejecuta `kafka-init`
- **THEN** el tópico queda con `retention.ms=604800000`

#### Scenario: Broker no disponible
- **GIVEN** el broker no responde
- **WHEN** se ejecuta `kafka-init`
- **THEN** termina con exit code distinto de 0 y los servicios que dependen de él no arrancan

### Requirement: Sin creación implícita de tópicos
El broker SHALL rechazar la producción o el consumo sobre un tópico inexistente, en lugar de crearlo con la configuración por defecto.

#### Scenario: Tópico con un typo
- **GIVEN** el broker está levantado
- **WHEN** un cliente produce en `transactions.event.v1` (inexistente)
- **THEN** el tópico no se crea y el cliente recibe un error de metadata por tópico desconocido

### Requirement: Key del mensaje por evento
Todo mensaje de `transactions.events.v1` SHALL tener como key el `event_id` del evento, para repartir la carga entre particiones aunque el tráfico por cliente sea muy desigual. No hay orden garantizado entre eventos de un mismo cliente: los reintentos ya lo rompen, y la idempotencia la da `(client_id, event_id)` en la base. Los mensajes de `notifications.replay-requested.v1` SHALL tener como key el `event_id`. Un mensaje que pasa a `notificator.retry.v1-<n>` o a `notificator.dlt.v1` SHALL conservar la key y el valor del mensaje que lo originó. Un mensaje que la recuperación del notificator publica en `notificator.retry.v1-<n>` SHALL tener como key el `event_id` del evento y un valor con el formato de `transactions.events.v1`, reconstruido desde la fila.

#### Scenario: Eventos de un mismo cliente
- **GIVEN** muchos eventos de `client_id=A` con distintos `event_id`, publicados en `transactions.events.v1`
- **WHEN** se revisa su partición
- **THEN** cada uno está en la partición que corresponde a su `event_id`, así que se reparten entre las particiones en vez de concentrarse en una

#### Scenario: El retry mantiene la key
- **GIVEN** un evento `EVT100` de `client_id=A` cuya entrega falla y pasa a `notificator.retry.v1-0`
- **WHEN** se lee el mensaje del tópico de retry
- **THEN** su key sigue siendo `EVT100` y su `client_id` no se alteró, así que la entrega no puede ir a la suscripción de otro cliente

#### Scenario: El retry de un replay mantiene la key del replay
- **GIVEN** un pedido de replay de `EVT003` (de `CLIENT002`) con key `EVT003` cuya entrega falla y pasa a `notificator.retry.v1-0`
- **WHEN** se lee el mensaje del tópico de retry
- **THEN** su key sigue siendo `EVT003` y su `client_id` sigue siendo `CLIENT002`, así que la entrega no puede ir a la suscripción de otro cliente

#### Scenario: Mensaje de recuperación
- **GIVEN** `EVT100` de `CLIENT001` está trabado en `RETRYING` con `attempts=2`
- **WHEN** la recuperación lo publica en `notificator.retry.v1-1`
- **THEN** el mensaje tiene key `EVT100` y un valor con `event_id=EVT100` y `client_id=CLIENT001`, igual a la fila
