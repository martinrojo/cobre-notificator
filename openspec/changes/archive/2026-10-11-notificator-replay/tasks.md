# Tasks

## 1. Esquema

- [x] 1.1 Agregar `generation INT NOT NULL DEFAULT 0` a `notification_event` en `notificator/db/01-schema.sql`, después de `attempts` (design §1). Verificar con `docker compose down -v && docker compose up -d mariadb`: la columna existe y el seed carga con `generation=0`.

## 2. Spike del listener multi-tópico

- [x] 2.1 Verificar en un test de integración, o en los sources de spring-kafka 4.1.1, que `@KafkaListener(topics = {transactions.events.v1, notifications.replay-requested.v1})` con el `@RetryableTopic` y el names provider de `KafkaRetryConfig` crea un solo consumer por `notificator.retry.v1-<n>` y por `notificator.dlt.v1` (design §3). Anotar el resultado en design §3. Si falla, seguir con el plan B en las tareas 5.x.

## 3. Manager

- [x] 3.1 Sumar `generation` al `SELECT` compartido, al `ROW_MAPPER` y a `NotificationEvent`, y agregar `generation` a `ReplayRequested(eventId, clientId, generation, requestedAt)`. `NotificationEventReplayService` la toma del evento leído (design §5).
- [x] 3.2 Actualizar los tests: `NotificationEventReplayServiceTest` verifica que publica la `generation` del evento, y `KafkaReplayRequestPublisherTest` el valor JSON con los cuatro campos (spec, Contenido del mensaje). También el test de integración del repositorio si mapea columnas.

## 4. Dominio y persistencia del notificator

- [x] 4.1 Agregar `generation` a `NotificationEvent` y crear el record `ReplayRequest(eventId, clientId, generation, requestedAt)`, que valida campos requeridos, largos y `generation >= 0` con `InvalidEventException`. Verificar con un test parametrizado.
- [x] 4.2 Agregar `generation` como parámetro y condición a `claimAttempt`, `markDelivered`, `recordAttempt`, `refreshIfStale` y `failIfStale`, y a la lectura de `findById` y `findStale` (design §1).
- [x] 4.3 Agregar `claimReplay(ReplayRequest)` con el `UPDATE` de design §1. Verificar en `JdbcNotificationEventRepositoryIntegrationTest`: gana sobre `FAILED` con la generación correcta y deja `generation+1`, `attempts=1`, `PENDING` y `last_replay_at`; pierde con otra generación, otro `client_id`, otro estado o un evento inexistente; una segunda llamada con la misma generación pierde. Cubrir también que cada `UPDATE` de 4.2 no afecta filas con otra generación aunque `attempts` coincida.

## 5. Consumo del replay y retries con generación

- [x] 5.1 Mapear `notifications.replay-requested.v1` al intento 1 en `TopicAttempts.attemptFor` y actualizar `TopicAttemptsTest`, que hoy espera excepción para ese tópico.
- [x] 5.2 Implementar el parseo de mensajes de retry y DLT: valor con `generation` → pedido de replay con generación g+1; sin `generation` → formato del principal, generación 0 (design §2). Verificar con un test para cada formato y para JSON inválido.
- [x] 5.3 `DeliveryService`: agregar `replay(ReplayRequest)` (claim y, si gana, entrega como intento 1 de la generación nueva; si pierde, descarte con métrica y log DEBUG) y pasar la generación por `retry` y por todas las escrituras de `deliver`. Verificar en `DeliveryServiceTest`: replay ganado, replay descartado, retry de otra generación descartado y 2xx de otra generación sin efecto.
- [x] 5.4 Listener: consumir los dos tópicos principales y despachar por tópico (design §3, o plan B según 2.1). Un pedido de replay inválido va al DLT, y el handler del DLT pasa la generación a `failExhausted`.
- [x] 5.5 `ExhaustedEventService.failExhausted` con generación. Verificar en su test que un DLT de otra generación no cambia la fila.

## 6. Recuperación

- [x] 6.1 `RetryPublisher`/`KafkaRetryPublisher`: con `generation > 0` republica un pedido de replay (key `event_id`, `generation - 1`, `requested_at = last_replay_at`); con `generation = 0`, igual que hoy (design §4).
- [x] 6.2 `StaleDeliveryRecoveryService` pasa la generación de la fila a `refreshIfStale`, `failIfStale` y al publisher. Verificar en su test el caso de una fila de replay trabada en `attempts=1`.

## 7. Observabilidad

- [x] 7.1 Counter `notificator.replays{outcome=started|discarded}`, log INFO `Replay started` y DEBUG `Replay discarded`, con `event_id`, `client_id` y `generation`, sin `content` (design §6). Verificar el counter en `DeliveryServiceTest`.

## 8. Integración y documentación

- [x] 8.1 Agregar a `TransactionEventFlowIntegrationTest` (o a un test hermano con `FlowTestSupport`): un evento `FAILED` recibe un pedido de replay y queda `DELIVERED` con `generation=1`; dos pedidos con la misma generación producen un solo POST; un replay con 503 pasa por `notificator.retry.v1-0` y se entrega en el intento 2.
- [x] 8.2 Correr `mvn -f manager/pom.xml verify` y `mvn -f notificator/pom.xml verify`.
- [x] 8.3 Actualizar `openspec/config.yaml` ("attempts nunca baja" pasa a valer dentro de cada generación; el claim lleva `generation`) y la sección de replay del `README.md`.

## Workflow follow-up

- Archivar `notificator-idempotent-delivery` antes que este cambio: los requisitos MODIFIED de `notification-delivery` viven todavía en él.
