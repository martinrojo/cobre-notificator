# Tasks

## 1. Esquema y configuración

- [x] 1.1 Agregar `idx_event_status_updated (delivery_status, updated_at)` en `notificator/db/01-schema.sql` (design §9). Verificar con `docker compose down -v && docker compose up -d mariadb`: `SHOW INDEX FROM notification_event` lista el índice y el seed carga.
- [x] 1.2 Crear `DeliveryProperties` (record con `@ConfigurationProperties`): retry (attempts 4, delay 60 s, multiplier 5), `lease` 120 s, webhook (connect 2 s, read 10 s, `allow-http` false), recovery (interval 60 s, batch 100). Configurar en `application.yml` el consumer (group `notificator`, String deserializers, `AckMode.RECORD`, concurrency 3), el producer (`acks=all`, idempotente), Jackson `SNAKE_CASE`, la zona UTC del datasource y un bean `Clock` UTC. Agregar las variables del webhook al servicio `notificator` de `docker-compose.yml`. Verificar que `mvn -f notificator/pom.xml verify` arranca el contexto.

## 2. Dominio

- [x] 2.1 Crear los records `TransactionEvent` (con validación de campos y largos, que lanza `InvalidEventException.of(reason)`), `NotificationEvent`, `Subscription`, el enum `DeliveryStatus` y el `WebhookResult` sellado (`Responded(status)` / `TransportError(reason)`). Verificar con `TransactionEventTest`, parametrizado: campos nulos, vacíos y de 65 caracteres → excepción; válido → ok.
- [x] 2.2 Implementar `DeliveryOutcome.of(WebhookResult)` (2xx → DELIVERED; transporte, 408, 429 y 5xx → RETRYABLE; resto, incluido 3xx → PERMANENT) y `RetrySchedule.delay(k)` (60, 300, 1500, 0). Verificar con tests parametrizados que cubran 200, 204, 302, 400, 404, 408, 429, 500, 503 y timeout.
- [x] 2.3 Crear los ports in/out de design §7 y `RetryableDeliveryException.of(eventId, attempt)`. Verificar que compila.

## 3. Persistencia

- [x] 3.1 Implementar `JdbcNotificationEventRepository` con las sentencias de design §1:
  - `insertClaim` devuelve `false` ante `DuplicateKeyException`;
  - `claimAttempt(id, n)`, `markRetrying`, `markFailed` y `markNotSubscribed` condicionales sobre `attempts`;
  - `markDelivered` sin condición sobre `attempts`;
  - `findById`;
  - cada método devuelve si afectó una fila.

  Verificar con un test de integración contra MariaDB (el de compose, perfil de test):
  - el segundo `insertClaim` del mismo id devuelve `false`;
  - `claimAttempt(id, 2)` dos veces → `true`, `false`;
  - `markRetrying` con un `attempts` viejo → `false` y la fila no cambia;
  - `markDelivered` sobre `FAILED` → `false`.
- [x] 3.2 Agregar a ese repositorio `findStale(lease, batch)`, `refreshIfStale(id, k, threshold)` y `failIfStale(id, threshold)` (design §5), con el tiempo de la DB. Verificar con un test de integración: una fila con `updated_at` viejo se refresca una sola vez en dos llamadas seguidas, y una fila dentro del umbral no se toca.
- [x] 3.3 Implementar `JdbcSubscriptionRepository.findActive(clientId, eventType)`. Verificar con un test de integración: suscripción inactiva → vacío, y la suscripción de otro cliente con el mismo `event_type` nunca se devuelve.

## 4. Webhook

- [x] 4.1 Implementar `RestClientWebhookClient` (design §6): POST JSON con `X-Event-Id` y `X-Delivery-Attempt`, `Redirect.NEVER`, timeouts, devolución de `WebhookResult` sin excepciones por status, y timer `notificator.webhook.latency`. Verificar con un test sobre `com.sun.net.httpserver.HttpServer` del JDK (sin dependencias nuevas): headers y cuerpo correctos, 302 → `Responded(302)` sin seguir, respuesta lenta → `TransportError`, puerto cerrado → `TransportError`.

## 5. Servicio de entrega

- [x] 5.1 Implementar `DeliveryService.first(event)` y `retry(eventId, attempt)` según design §1 y §3:
  - claim;
  - suscripción desde la fila;
  - control de `https`;
  - POST;
  - escritura condicional;
  - `RetryableDeliveryException` solo si `markRetrying` afectó una fila.

  Incluir el counter `notificator.deliveries{outcome,client_id}`, `notificator.discarded{reason}` y los logs de design §8. Verificar con `DeliveryServiceTest` (Mockito, `SimpleMeterRegistry`):
  - duplicado → sin POST y `reason=duplicate`;
  - claim perdido → sin POST y `reason=stale`;
  - sin suscripción → `markNotSubscribed` sin POST;
  - `http` no permitido → `FAILED` sin POST;
  - 204 → `markDelivered`;
  - 503 con CAS ganado → excepción;
  - 503 con CAS perdido → sin excepción;
  - 400 → `markFailed`;
  - la suscripción se busca con el `client_id` de la fila aunque el mensaje traiga otro;
  - ningún log contiene `content` (captura del appender).
- [x] 5.2 Implementar `ExhaustedEventService` (DLT → `FAILED` con el `attempts` del intento de origen, contando `notificator.deliveries{outcome=failed,client_id}`). El counter `notificator.dlt.messages{reason}` va en el handler del DLT (6.2), que es quien conoce el motivo. Verificar con un test:
  - fila `RETRYING attempts=4` → `FAILED`;
  - fila `DELIVERED` → sin cambios;
  - sin fila → sin error.

## 6. Kafka

- [x] 6.1 Implementar `TopicAttempts` (tópico → intento y su inversa, design §2). Verificar con un test: el principal → 1, `retry.v1-0..2` → 2..4, intento 2..4 → `retry.v1-0..2`.
- [x] 6.2 Implementar `KafkaRetryConfig`: `RetryTopicNamesProviderFactory` propio, `autoCreateTopics=false`, `include = RetryableDeliveryException`, y `configureBlockingRetries` para las excepciones de DB transitorias con backoff exponencial de hasta 30 s. Implementar también `TransactionEventListener` con su `@DltHandler`. El handler del DLT toma el intento del header `retry_topic-attempts` − 1, y un mensaje inválido va al DLT con `reason=invalid`. Verificar con un test de integración contra el Kafka de compose:
  - un evento con webhook que responde 503 recorre `retry.v1-0`, con delays acortados en el perfil de test, y termina `FAILED` con `attempts=4` tras pasar por `notificator.dlt.v1`;
  - un JSON inválido llega al DLT sin fila;
  - el mensaje de retry conserva la key `client_id`.
- [x] 6.3 Verificar el reintento bloqueante con un test de integración: con la DB detenida (`docker compose stop mariadb`) se publica un evento, `notificator.retry.v1-*` y `notificator.dlt.v1` no reciben mensajes, y al volver la DB el evento queda `DELIVERED`.

## 7. Recuperación

- [x] 7.1 Implementar `KafkaRetryPublisher` (envío síncrono a `retry.v1-(k-1)`, key `client_id`, valor reconstruido desde la fila y header `retry_topic-attempts = k + 1`) y `StaleDeliveryRecoveryService`:
  - si `k<4`, refresh y publica;
  - si `k=4`, `failIfStale`;
  - si la publicación falla, log ERROR y sigue con la próxima fila;
  - counter `notificator.recovery.events{action}`.

  Verificar con `StaleDeliveryRecoveryServiceTest` (Mockito):
  - `k=1` → publica en `retry.v1-0`;
  - `k=2` → en `retry.v1-1`;
  - `k=4` → `FAILED` sin publicar;
  - refresh perdido → no publica;
  - un fallo de publicación no corta el lote.
- [x] 7.2 Implementar `StaleDeliveryRecoveryJob` (`@Scheduled(fixedDelayString=...)`, `@EnableScheduling`). Verificar con un test de integración: una fila `PENDING attempts=1` con `updated_at` viejo y el webhook respondiendo 200 termina en `DELIVERED` con `attempts=2`, y un `INSERT` duplicado del mismo evento en el principal no la altera.

## 8. Documentación e integración

- [x] 8.1 Actualizar `docs/flujo-notificator.mmd` con el flujo de este diseño: claim antes del POST, descarte por 0 filas sin excepción, reintento bloqueante de DB y job de recuperación. Actualizar `README.md` (sección del notificator: estados, `attempts`, at-least-once con `X-Event-Id`, métricas) y la sección Notificator y Kafka de `openspec/config.yaml` (claim, recuperación, errores de DB bloqueantes). Verificar que el `.mmd` renderiza (por ejemplo en mermaid.live) sin errores.
- [x] 8.2 Prueba end-to-end con `docker compose up`:
  - publicar con `kafka-console-producer` el mismo evento dos veces seguidas, con un webhook local que responde 200;
  - `GET /notification_events/{id}` del manager muestra `DELIVERED` con `attempts=1`;
  - el webhook recibió un solo POST;
  - `/actuator/prometheus` muestra `notificator_discarded_total{reason="duplicate"} 1`.
