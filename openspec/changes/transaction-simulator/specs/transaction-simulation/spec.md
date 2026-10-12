# Spec Delta

## Purpose

Simular localmente a la plataforma que produce `transactions.events.v1`, publicando eventos sintéticos válidos de varios clientes a una tasa conocida para ejercitar el notificator de punta a punta.

## ADDED Requirements

### Requirement: Formato del mensaje simulado
Cada mensaje que publique el simulator SHALL cumplir el contrato de `transactions.events.v1`: JSON con `event_id`, `client_id`, `event_type` (no vacíos, hasta 64 caracteres), `content` (string) y `created_at` (instante ISO-8601 UTC del momento de generación). El `event_id` SHALL ser único por mensaje.

#### Scenario: Mensaje generado
- **GIVEN** el simulator está corriendo
- **WHEN** se lee un mensaje de `transactions.events.v1`
- **THEN** su valor es `{"event_id":"EVT-<uuid>","client_id":"CLIENT003","event_type":"credit_deposit","content":"...","created_at":"2026-10-11T10:00:00.123Z"}` (valores de ejemplo) y el notificator lo acepta sin mandarlo a `notificator.dlt.v1`

#### Scenario: Sin event_id repetidos
- **GIVEN** el simulator publicó 10 000 mensajes
- **WHEN** se agrupan por `event_id`
- **THEN** cada `event_id` aparece una sola vez, así que ningún mensaje es descartado como duplicado por el notificator

### Requirement: Clientes simulados
El simulator SHALL generar eventos para exactamente 8 clientes, `CLIENT001` a `CLIENT008`, con un reparto aproximadamente uniforme. El `event_type` SHALL elegirse al azar de los tipos de `notification_events.json`.

#### Scenario: Reparto entre clientes
- **GIVEN** el simulator corrió 1 minuto con la tasa por defecto
- **WHEN** se cuentan los mensajes por `client_id`
- **THEN** aparecen los 8 clientes, ningún otro `client_id`, y cada uno tiene 375 ± 10 % mensajes

### Requirement: Key por cliente
Cada mensaje SHALL publicarse con key igual a su `client_id`, de modo que los eventos de un cliente caigan en una misma partición y nunca se mezclen con la key de otro cliente.

#### Scenario: Key del mensaje
- **WHEN** el simulator publica un evento con `client_id=CLIENT005`
- **THEN** la key del mensaje es `CLIENT005` y todos los mensajes de `CLIENT005` están en la misma partición

#### Scenario: Aislamiento entre clientes
- **GIVEN** un mensaje con key `CLIENT001`
- **WHEN** se lee su valor
- **THEN** su `client_id` es `CLIENT001`; nunca hay un mensaje cuya key y `client_id` difieran

### Requirement: Tasa de publicación
El simulator SHALL publicar a una tasa total configurable, por defecto 3000 eventos por minuto, repartida en partes iguales entre los 8 clientes. Un error de publicación SHALL registrarse en log ERROR sin el `content` y no SHALL detener la generación.

#### Scenario: Tasa por defecto
- **GIVEN** el simulator arranca sin configuración de tasa
- **WHEN** corre 1 minuto con el broker disponible
- **THEN** se publican 3000 ± 5 % mensajes en `transactions.events.v1`

#### Scenario: Tasa configurada
- **GIVEN** la tasa está configurada en 600 eventos por minuto
- **WHEN** corre 1 minuto
- **THEN** se publican 600 ± 5 % mensajes, 75 ± 10 % por cliente

#### Scenario: Broker caído temporalmente
- **GIVEN** el simulator está corriendo
- **WHEN** el broker deja de responder y luego vuelve
- **THEN** los envíos fallidos se registran en log ERROR con `event_id` y `client_id` (sin `content`), el proceso sigue vivo y la publicación se reanuda cuando el broker vuelve

### Requirement: Arranque opcional en el entorno local
El simulator SHALL estar en `docker-compose.yml` sin arrancar con un `docker compose up` normal; solo arranca al habilitarlo explícitamente, y siempre después de que los tópicos existan.

#### Scenario: Entorno por defecto
- **WHEN** se ejecuta `docker compose up`
- **THEN** el simulator no arranca y no se publica ningún mensaje simulado

#### Scenario: Habilitado explícitamente
- **WHEN** se ejecuta `docker compose --profile simulator up`
- **THEN** el simulator arranca después de que `kafka-init` termina con exit code 0 y empieza a publicar
