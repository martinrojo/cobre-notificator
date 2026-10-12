# Proposal

## Why

Hoy el manager publica los pedidos de replay en `notifications.replay-requested.v1` con key `client_id`. Un replay es por evento y no necesita ordenarse con los demás eventos del cliente. Con key `client_id`, un cliente que pide muchos replays manda todos a la misma partición. Con key `event_id`, los pedidos se reparten entre particiones, y los pedidos duplicados de un mismo evento siguen cayendo en la misma partición y en orden, que es lo que necesita la transición condicional del notificator para descartarlos.

## What Changes

- El pedido de replay se publica con key igual al `event_id` en vez del `client_id`. El valor del mensaje no cambia (`event_id`, `client_id`, `requested_at`). **BREAKING** para cualquier consumidor que dependa de la key; hoy no hay ninguno (el notificator todavía no consume el tópico de replay).
- La regla "key = `client_id`" de los tópicos del flujo deja de valer para el tópico de replay. Un mensaje que pasa a retry o DLT conserva la key del mensaje que lo originó. Un replay que se reintenta sigue con key `event_id`.
- El aislamiento por cliente no depende de la key: el notificator siempre usa el `client_id` del valor del mensaje.

Aplicaciones afectadas: **manager** (publisher y su test). **notificator**: no cambia código; solo aplica la regla de key nueva cuando se implemente el consumo del replay. **gateway**: no se toca.

Fuera de alcance:
- Cambiar la key de `transactions.events.v1` (sigue siendo `client_id`).
- Implementar el consumidor de replay en el notificator.
- Cambiar el número de particiones o la configuración de los tópicos.
- Migrar los mensajes que ya están en el tópico (retención de 7 días; en local alcanza con recrear el entorno).

## Capabilities

### New Capabilities

_Ninguna._

### Modified Capabilities

- `notification-event-replay`: el requirement "Mensaje de pedido de replay" cambia la key de `client_id` a `event_id`.
- `event-topics`: el requirement "Key del mensaje por cliente" excluye el tópico de replay y define que retry y DLT conservan la key del mensaje de origen.

## Impact

- `manager/.../messaging/KafkaReplayRequestPublisher.java`: usa `request.eventId()` como key.
- `manager/.../messaging/KafkaReplayRequestPublisherTest.java`: el test `sendsJsonKeyedByClientId` pasa a verificar la key `EVT003`.
- `README.md` (sección de tópicos) y el `context` de `openspec/config.yaml` ("Key de los mensajes: client_id"): se actualizan con la regla nueva.
- No hay dependencias nuevas, ni cambios de DB ni de API HTTP.
