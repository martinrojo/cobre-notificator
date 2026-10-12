# Tasks

## 1. Manager: key del replay

- [x] 1.1 En `KafkaReplayRequestPublisher.publish` usar `request.eventId()` como key en `kafkaTemplate.send(...)`. Renombrar el test `sendsJsonKeyedByClientId` a `sendsJsonKeyedByEventId` y hacer que verifique la key `EVT003` con el mismo valor JSON. Verificar con `mvn test` en `manager` (incluye el check de `fmt-maven-plugin`).

## 2. Documentación y contexto

- [x] 2.1 En `README.md`, cambiar "La key de todos los mensajes es el `client_id`." por la regla nueva: `client_id` en `transactions.events.v1`, `event_id` en `notifications.replay-requested.v1`, y retry/DLT conservan la key de origen. Verificar leyendo la sección de tópicos.
- [x] 2.2 En el `context` de `openspec/config.yaml`, actualizar "Key de los mensajes: client_id" con la misma regla. Verificar con `openspec context --json` que el YAML sigue parseando.

## 3. Verificación integrada

- [x] 3.1 Con `docker compose up`, hacer `POST /notification_events/EVT003/replay` con `X-Client-Id: CLIENT002` (respuesta 202) y leer el tópico con `kafka-console-consumer.sh --property print.key=true`. Verificar que la key es `EVT003` y que el valor no cambió.
