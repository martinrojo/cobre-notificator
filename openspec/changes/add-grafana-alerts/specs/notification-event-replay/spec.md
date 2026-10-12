# Spec Delta

## MODIFIED Requirements

### Requirement: 202 solo con confirmación de Kafka
El endpoint SHALL responder HTTP 202 a un evento `FAILED` solo después de que Kafka confirme la escritura con todas las réplicas en sincronía. Si la publicación falla o no se confirma dentro del timeout configurado, SHALL responder HTTP 503 en formato Problem Details con un header `Retry-After` en segundos. Los pedidos concurrentes pueden generar más de una publicación.

#### Scenario: Kafka caído
- **GIVEN** `EVT003` pertenece a `CLIENT002`, está en `FAILED` y el broker no responde
- **WHEN** se hace `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 503 con `Content-Type: application/problem+json` y `Retry-After: 5`, dentro del tiempo máximo de bloqueo más el timeout de publicación configurados

#### Scenario: Host del broker que no resuelve
- **GIVEN** `EVT003` pertenece a `CLIENT002`, está en `FAILED` y el host del broker no resuelve por DNS
- **WHEN** se hace `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 503 con `Content-Type: application/problem+json` y `Retry-After: 5`, no HTTP 500

#### Scenario: Reintento del cliente después de un 503
- **GIVEN** un pedido de `EVT003` respondió 503 y el broker volvió a estar disponible
- **WHEN** se repite `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002`
- **THEN** la respuesta es HTTP 202 y se publica el pedido

#### Scenario: Pedidos concurrentes
- **GIVEN** `EVT003` pertenece a `CLIENT002` y está en `FAILED`
- **WHEN** llegan dos `POST /notification_events/EVT003/replay` simultáneos con `X-Client-Id: CLIENT002`
- **THEN** ambos responden HTTP 202, y puede haber hasta dos pedidos de replay de `EVT003` publicados
