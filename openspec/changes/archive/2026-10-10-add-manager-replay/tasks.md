# Tasks

## 1. Esquema y tópicos

- [x] 1.1 Agregar `last_replay_at DATETIME(3) NULL` después de `last_attempt_at` en `notificator/db/01-schema.sql`. Verificar con `docker compose down -v && docker compose up -d mariadb` y `DESCRIBE notification_event`: la columna está, el seed carga y queda nula en todos los eventos.
- [x] 1.2 En `kafka/create-topics.sh`, reemplazar `notificator.replay.v1` por `notifications.replay-requested.v1` (7 días). Actualizar los nombres en `README.md` (tabla de tópicos y ACL del manager) y en `openspec/config.yaml` (API del manager y Notificator y Kafka). Verificar que, después de `docker compose up kafka-init`, `kafka-topics.sh --describe` lista los 6 tópicos de la spec `event-topics` con 3 particiones, que `notifications.replay-requested.v1` tiene `retention.ms=604800000` y que `notificator.replay.v1` no existe.

## 2. Dominio

- [x] 2.1 Agregar `Instant lastReplayAt` a `NotificationEvent`, el enum `ReplayDecision` y el método `replayDecision()` según la tabla de design §1. Ajustar los constructores existentes en tests. Verificar con `NotificationEventTest`, parametrizado con todas las combinaciones de `DeliveryStatus` × `lastReplayAt` nulo/no nulo.
- [x] 2.2 Crear el record `ReplayRequested`, el port out `ReplayRequestPublisher` y el port in `ReplayNotificationEventUseCase`. Crear también las excepciones `ReplayNotAllowedException.of(eventId, status)` y `ReplayPublishException.of(eventId, cause)`. Verificar que `mvn -f manager/pom.xml compile` pasa.

## 3. Aplicación

- [x] 3.1 Implementar `NotificationEventReplayService` con `Clock`, `MeterRegistry`, logs y el counter `manager.replay.requests{outcome}` de design §8. Verificar con `NotificationEventReplayServiceTest` (Mockito, `Clock.fixed`, `SimpleMeterRegistry`):
  - `FAILED` → publica `ReplayRequested(eventId, clientId, now)` y `outcome=published`;
  - `PENDING`/`RETRYING` con `last_replay_at` → no publica y `in_progress`;
  - `DELIVERED` → `ReplayNotAllowedException`, no publica y `not_allowed`;
  - `Optional.empty()` → `NotificationEventNotFoundException` y `not_found`;
  - el publisher lanza `ReplayPublishException` → se propaga y `unavailable`;
  - el repositorio recibe el client_id y el event_id tal cual.

## 4. Persistencia

- [x] 4.1 Agregar `last_replay_at` al `SELECT` y al `ROW_MAPPER` de `JdbcNotificationEventRepository`, con conversión UTC explícita. Verificar que `mvn -f manager/pom.xml verify` pasa y que `GET /notification_events/EVT001` no cambia su respuesta.

## 5. Kafka

- [x] 5.1 Agregar `spring-boot-starter-kafka` al `pom.xml` del manager y la configuración de design §5 en `application.yml`, con el record `ReplayProperties`. Agregar un bean `Clock` UTC. Verificar que el contexto arranca con `mvn -f manager/pom.xml verify`.
- [x] 5.2 Implementar `KafkaReplayRequestPublisher` (design §3 y §4). Verificar con `KafkaReplayRequestPublisherTest` (Mockito sobre `KafkaTemplate`):
  - se envía al tópico configurado con key `client_id` y valor exacto `{"event_id":"EVT003","client_id":"CLIENT002","requested_at":"2026-10-10T12:00:00.123Z"}`;
  - un future fallido → `ReplayPublishException`;
  - un future que nunca completa → `ReplayPublishException` al vencer `publish-timeout`;
  - `send()` que lanza `KafkaException` → `ReplayPublishException`;
  - con interrupción → `ReplayPublishException` y el flag de interrupción queda restaurado.
- [x] 5.3 En `docker-compose.yml`, agregar `KAFKA_BOOTSTRAP_SERVERS: kafka:9092` al manager y `depends_on: kafka-init: condition: service_completed_successfully`. Verificar que `docker compose up -d --build manager` arranca después de `kafka-init`.

## 6. Adapter web

- [x] 6.1 Agregar `POST /notification_events/{notification_event_id}/replay` a `NotificationEventController` (202 sin cuerpo). Agregar a `ApiExceptionHandler` los handlers 409 y 503 con `Retry-After` en segundos (design §6). Verificar con `NotificationEventControllerTest`:
  - 202 sin cuerpo;
  - 404 con el mismo `title` y `detail` que el GET;
  - 409 `application/problem+json` con el estado en el `detail`;
  - 503 con `Retry-After: 5` y `detail` genérico, sin la causa técnica;
  - 401 sin header y el use case no se invoca;
  - el client_id del header y el id del path llegan al use case.

## 7. Métricas

- [x] 7.1 Agregar `spring-boot-starter-actuator` y `micrometer-registry-prometheus`. Configurar `management.server.port: 9291` y exponer solo `health` y `prometheus`; no publicar el puerto en el compose. Verificar:
  - dentro del contenedor, `curl localhost:9291/actuator/prometheus` responde 200 sin `X-Client-Id` y muestra `manager_replay_requests_total` después de un replay;
  - `curl localhost:9290/actuator/prometheus` no lo sirve: 401 o 404.

## 8. Integración

- [x] 8.1 Con `docker compose up -d --build`, ejecutar con `curl` los escenarios de la spec `notification-event-replay`:
  - `EVT003` con `CLIENT002` → 202, y el mensaje aparece con key `CLIENT002` en `kafka-console-consumer.sh --topic notifications.replay-requested.v1 --property print.key=true --from-beginning`;
  - `EVT001` con `CLIENT001` → 409;
  - `EVT003` con `CLIENT001` → 404;
  - `EVT999` → 404 con el mismo cuerpo;
  - sin header → 401.

  Simular el replay en curso con un `UPDATE` manual como root (`delivery_status='PENDING', last_replay_at=NOW(3)`) → 202 sin mensaje nuevo. Verificar que los logs no tienen `content`.
- [x] 8.2 Con `docker compose stop kafka`, ejecutar el replay de `EVT003`. Verificar que responde 503 con `Retry-After: 5` en menos de `max.block.ms + publish-timeout` (~4 s), que `GET /notification_events` sigue respondiendo 200 y que el log tiene un ERROR con `event_id`. Después, `docker compose start kafka` y repetir el pedido: → 202.

## Workflow follow-up

- Archivar el cambio una vez revisado. El archive actualiza `openspec/specs/event-topics` y crea `openspec/specs/notification-event-replay`.
