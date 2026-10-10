# event-topics Specification

## Purpose

Define los tópicos de Kafka del flujo de notificaciones (principal, reintentos, DLT y replay): sus nombres, particiones y retención, y cómo se aprovisionan de forma reproducible en el entorno local.

## Requirements

### Requirement: Tópicos del flujo de notificaciones
El entorno SHALL tener exactamente estos tópicos del flujo, cada uno con 3 particiones: `transactions.events.v1`, `notificator.retry.v1-0`, `notificator.retry.v1-1`, `notificator.retry.v1-2`, `notificator.dlt.v1` y `notifications.replay-requested.v1`. Los tópicos con el prefijo `notificator.` son internos del notificator. `notifications.replay-requested.v1` es el contrato entre el manager, que produce, y el notificator, que consume.

#### Scenario: Tópicos creados al levantar el entorno
- **GIVEN** un entorno sin volumen de Kafka
- **WHEN** se ejecuta `docker compose up` y `kafka-init` termina con exit code 0
- **THEN** `kafka-topics.sh --describe` lista los 6 tópicos, cada uno con `PartitionCount: 3`

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
- **THEN** termina con exit code 0 y los 6 tópicos conservan 3 particiones y su retención declarada

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

### Requirement: Key del mensaje por cliente
Todo mensaje de los tópicos del flujo SHALL tener como key el `client_id` del evento, para que los eventos de un mismo cliente caigan en la misma partición y mantengan su orden relativo.

#### Scenario: Dos eventos del mismo cliente
- **GIVEN** dos eventos de `client_id=A` publicados en `transactions.events.v1`
- **WHEN** se revisa su partición
- **THEN** ambos están en la misma partición, en el orden en que se publicaron

#### Scenario: El retry mantiene la key
- **GIVEN** un evento de `client_id=A` cuya entrega falla y pasa a `notificator.retry.v1-0`
- **WHEN** se lee el mensaje del tópico de retry
- **THEN** su key sigue siendo `A` y su `client_id` no se alteró, así que la entrega no puede ir a la suscripción de otro cliente
