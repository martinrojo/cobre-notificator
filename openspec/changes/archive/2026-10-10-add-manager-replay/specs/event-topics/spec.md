# Spec Delta

## MODIFIED Requirements

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
