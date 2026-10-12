# Spec Delta

## Purpose

Simular localmente los webhooks de los clientes, con código de respuesta y latencia configurables en caliente por cliente, para ejercitar entregas, reintentos, circuit breaker, DLT y replay del notificator.

## ADDED Requirements

### Requirement: Recepción de webhooks
El mock SHALL aceptar `POST /webhooks/{client_id}` con cualquier cuerpo JSON y responder, tras la demora configurada para ese `client_id`, con el código HTTP configurado y cuerpo vacío. Sin configuración propia del cliente SHALL usar el comportamiento por defecto, que al arrancar es `200` sin demora.

#### Scenario: Comportamiento inicial
- **GIVEN** el mock acaba de arrancar
- **WHEN** el notificator hace `POST /webhooks/CLIENT001` con el evento `EVT100`
- **THEN** el mock responde `200` de inmediato y `EVT100` queda en `DELIVERED`

#### Scenario: Cliente con error configurado
- **GIVEN** `CLIENT002` está configurado con `status=503`
- **WHEN** el notificator entrega `EVT200` de `CLIENT002`
- **THEN** el mock responde `503` y `EVT200` queda en `RETRYING`

#### Scenario: Error no retryable
- **GIVEN** `CLIENT002` está configurado con `status=400`
- **WHEN** el notificator entrega `EVT201` de `CLIENT002`
- **THEN** el mock responde `400` y `EVT201` queda en `FAILED` sin reintentos

#### Scenario: Demora mayor al timeout del notificator
- **GIVEN** `CLIENT003` está configurado con `delay_ms=15000` y el notificator tiene `WEBHOOK_READ_TIMEOUT=10s`
- **WHEN** el notificator entrega `EVT300` de `CLIENT003`
- **THEN** el notificator corta por timeout y `EVT300` queda en `RETRYING`

### Requirement: Configuración por defecto
`PUT /config` SHALL reemplazar el comportamiento por defecto con un JSON `{"status": <int>, "delay_ms": <int>}` y responder `200` con el comportamiento guardado. El cambio SHALL aplicar a los requests siguientes de todo cliente sin override, sin reiniciar el mock.

#### Scenario: Cambiar el defecto
- **GIVEN** no hay overrides
- **WHEN** se hace `PUT /config` con `{"status":500,"delay_ms":2000}`
- **THEN** responde `200` y todo `POST /webhooks/{client_id}` siguiente responde `500` tras ~2 s

### Requirement: Configuración por cliente
`PUT /config/clients/{client_id}` SHALL fijar un override `{"status", "delay_ms"}` solo para ese cliente y responder `200`. `DELETE /config/clients/{client_id}` SHALL quitarlo (el cliente vuelve al defecto) y responder `204`, exista o no. `GET /config` SHALL devolver `200` con el defecto y todos los overrides.

#### Scenario: Override aislado por cliente
- **GIVEN** `PUT /config/clients/CLIENT004` con `{"status":503,"delay_ms":0}`
- **WHEN** el notificator entrega un evento de `CLIENT004` y otro de `CLIENT005`
- **THEN** `CLIENT004` recibe `503` y su evento queda en `RETRYING`, mientras `CLIENT005` recibe `200` y su evento queda en `DELIVERED`

#### Scenario: Reset de un cliente
- **GIVEN** `CLIENT004` tiene override `503` y el defecto es `200`
- **WHEN** se hace `DELETE /config/clients/CLIENT004`
- **THEN** responde `204` y el siguiente `POST /webhooks/CLIENT004` responde `200`

#### Scenario: Consultar configuración
- **GIVEN** defecto `{"status":200,"delay_ms":0}` y override de `CLIENT004` `{"status":503,"delay_ms":0}`
- **WHEN** se hace `GET /config`
- **THEN** responde `200` con `{"default":{"status":200,"delay_ms":0},"clients":{"CLIENT004":{"status":503,"delay_ms":0}}}`

### Requirement: Validación de la configuración
`status` SHALL estar entre 200 y 599 y `delay_ms` entre 0 y 60000. Un valor fuera de rango o faltante SHALL responder `400` con un mensaje que nombre el campo, sin modificar la configuración vigente.

#### Scenario: Status inválido
- **WHEN** se hace `PUT /config/clients/CLIENT001` con `{"status":99,"delay_ms":0}`
- **THEN** responde `400` mencionando `status` y `CLIENT001` sigue con su comportamiento anterior

#### Scenario: Demora fuera de rango
- **WHEN** se hace `PUT /config` con `{"status":200,"delay_ms":120000}`
- **THEN** responde `400` mencionando `delay_ms` y el defecto no cambia

### Requirement: Detección de entregas cruzadas
Si el `client_id` del cuerpo recibido no coincide con el `client_id` del path, el mock SHALL registrar un log ERROR con ambos `client_id` y el `event_id`, y responder igual que para ese path. El mock SHALL NOT registrar el campo `content` en ningún nivel de log.

#### Scenario: Evento de otro cliente
- **WHEN** llega a `POST /webhooks/CLIENT001` un cuerpo con `client_id=CLIENT002` y `event_id=EVT400`
- **THEN** el mock registra un log ERROR con `CLIENT001`, `CLIENT002` y `EVT400`, sin el `content`

#### Scenario: Evento del cliente correcto
- **WHEN** llega a `POST /webhooks/CLIENT001` un cuerpo con `client_id=CLIENT001`
- **THEN** no se registra ningún log ERROR

### Requirement: Suscripciones locales hacia el mock
El entorno local SHALL arrancar con suscripciones activas para `CLIENT001` a `CLIENT008` en los 10 tipos de evento de `notification_events.json`, cada una con `webhook_url=http://webhook-mock:8090/webhooks/<client_id>`.

#### Scenario: Evento simulado entregado
- **GIVEN** un volumen de MariaDB nuevo, el entorno levantado y el mock con su configuración inicial
- **WHEN** llega a `transactions.events.v1` un evento de `CLIENT006` con `event_type=debit_purchase`
- **THEN** el mock recibe `POST /webhooks/CLIENT006` y el evento queda en `DELIVERED`, no en `NOT_SUBSCRIBED`

#### Scenario: Cliente sin suscripción
- **WHEN** llega un evento de `CLIENT009`
- **THEN** el mock no recibe ningún request y el evento queda en `NOT_SUBSCRIBED`
