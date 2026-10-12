## MODIFIED Requirements

### Requirement: Key del mensaje por cliente
Todo mensaje de `transactions.events.v1` SHALL tener como key el `client_id` del evento, para que los eventos de un mismo cliente caigan en la misma partición y mantengan su orden relativo. Los mensajes de `notifications.replay-requested.v1` SHALL tener como key el `event_id`. Un mensaje que pasa a `notificator.retry.v1-<n>` o a `notificator.dlt.v1` SHALL conservar la key y el valor del mensaje que lo originó.

#### Scenario: Dos eventos del mismo cliente
- **GIVEN** dos eventos de `client_id=A` publicados en `transactions.events.v1`
- **WHEN** se revisa su partición
- **THEN** ambos están en la misma partición, en el orden en que se publicaron

#### Scenario: El retry mantiene la key
- **GIVEN** un evento de `client_id=A` cuya entrega falla y pasa a `notificator.retry.v1-0`
- **WHEN** se lee el mensaje del tópico de retry
- **THEN** su key sigue siendo `A` y su `client_id` no se alteró, así que la entrega no puede ir a la suscripción de otro cliente

#### Scenario: El retry de un replay mantiene la key del replay
- **GIVEN** un pedido de replay de `EVT003` (de `CLIENT002`) con key `EVT003` cuya entrega falla y pasa a `notificator.retry.v1-0`
- **WHEN** se lee el mensaje del tópico de retry
- **THEN** su key sigue siendo `EVT003` y su `client_id` sigue siendo `CLIENT002`, así que la entrega no puede ir a la suscripción de otro cliente
