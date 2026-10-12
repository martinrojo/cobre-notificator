# Spec Delta

## MODIFIED Requirements

### Requirement: Observabilidad de la entrega
El notificator SHALL exponer métricas de:
- entregas por resultado y `client_id`, incluidos los eventos que el job de recuperación cierra como `FAILED`;
- intentos al webhook por `client_id`, número de intento, código HTTP o error de transporte (`timeout`, `connect_error`, `io_error`) y resultado;
- latencia del webhook por `client_id` y resultado;
- latencia de punta a punta, desde `created_at` del evento hasta `DELIVERED`, solo para la generación 0;
- duplicados, intentos obsoletos y mensajes del DLT ignorados, como descartes;
- errores de persistencia reintentados en el mismo offset;
- mensajes en el DLT;
- eventos recuperados.

Los contadores con tags de valores conocidos SHALL existir en 0 desde el arranque.

SHALL registrar INFO en `DELIVERED` y `FAILED`, ERROR en intentos que se reintentan y en el DLT, y DEBUG en descartes. Ningún log ni métrica incluye `content`.

#### Scenario: Métrica por resultado
- **WHEN** `EVT100` de `CLIENT001` queda en `DELIVERED`
- **THEN** `/actuator/prometheus` muestra el contador de entregas con `outcome="delivered"` y `client_id="CLIENT001"` incrementado en 1

#### Scenario: Sin contenido en los logs
- **WHEN** se procesa cualquier evento, en cualquier nivel de log
- **THEN** ninguna línea de log contiene el valor de `content`

#### Scenario: Sin contenido en las métricas
- **WHEN** se procesa cualquier evento
- **THEN** ningún tag de ninguna métrica de `/actuator/prometheus` contiene el valor de `content` ni la URL del webhook

#### Scenario: Intento con código HTTP
- **GIVEN** el webhook de `CLIENT002` responde HTTP 503
- **WHEN** el notificator ejecuta el intento 2 de `EVT200` de `CLIENT002`
- **THEN** el contador de intentos con `client_id="CLIENT002"`, `attempt="2"`, `status="503"` y `outcome="retryable"` aumenta en 1, y `EVT200` queda en `RETRYING`

#### Scenario: Intento con timeout
- **GIVEN** el webhook de `CLIENT003` tarda más que el read-timeout
- **WHEN** el notificator ejecuta el intento 1 de un evento de `CLIENT003`
- **THEN** el contador de intentos con `client_id="CLIENT003"`, `attempt="1"`, `status="timeout"` y `outcome="retryable"` aumenta en 1

#### Scenario: Error permanente
- **GIVEN** el webhook de `CLIENT004` responde HTTP 404
- **WHEN** el notificator ejecuta el intento 1 de `EVT400`
- **THEN** el contador de intentos con `status="404"` y `outcome="permanent"` aumenta en 1, `EVT400` queda en `FAILED` y el contador de entregas con `outcome="failed"` y `client_id="CLIENT004"` aumenta en 1

#### Scenario: Latencia del webhook por cliente
- **WHEN** el webhook de `CLIENT001` responde HTTP 200
- **THEN** la latencia del webhook se registra con `client_id="CLIENT001"` y `outcome="delivered"`, y los otros clientes no se ven afectados

#### Scenario: Latencia de punta a punta
- **GIVEN** `EVT100` con `created_at` 90 s antes de que el webhook responda HTTP 200
- **WHEN** `EVT100` queda en `DELIVERED` en la generación 0
- **THEN** la latencia de punta a punta registra una observación de aproximadamente 90 s

#### Scenario: Replay sin latencia de punta a punta
- **WHEN** un replay de `EVT003` de `CLIENT002` lo deja en `DELIVERED` en la generación 1
- **THEN** la latencia de punta a punta no registra ninguna observación, y el contador de entregas con `outcome="delivered"` y `client_id="CLIENT002"` aumenta en 1

#### Scenario: FAILED cerrado por recuperación
- **GIVEN** `EVT300` de `CLIENT001` en `RETRYING` con `attempts=4` sin avanzar por más del umbral
- **WHEN** el job de recuperación lo pasa a `FAILED`
- **THEN** aumentan en 1 tanto el contador de recuperación con `action="failed"` como el de entregas con `outcome="failed"` y `client_id="CLIENT001"`

#### Scenario: Mensaje del DLT ignorado
- **GIVEN** `EVT003` ya pasó a la generación 1 por un replay
- **WHEN** llega al DLT un mensaje de `EVT003` de la generación 0
- **THEN** la fila no cambia y el contador de descartes con `reason="dlt_ignored"` aumenta en 1

#### Scenario: Error de persistencia
- **GIVEN** MariaDB no responde
- **WHEN** el notificator procesa un mensaje y la escritura falla con un error transitorio
- **THEN** el contador de errores de persistencia aumenta en cada reintento en el mismo offset, y el mensaje no pasa a los tópicos de retry ni al DLT

#### Scenario: Contadores en cero al arrancar
- **WHEN** el notificator arranca y todavía no procesó ningún mensaje
- **THEN** `/actuator/prometheus` muestra en 0 los contadores de DLT por `reason` (`exhausted`, `invalid`, `unexpected`), de recuperación por `action` (`republished`, `failed`) y de descartes por `reason` (`duplicate`, `stale`, `dlt_ignored`)
