# Tasks

## 1. Esquema

- [x] 1.1 En `notificator/db/01-schema.sql`, agregar `id UUID NOT NULL DEFAULT UUID_v7()` como primera columna de `notification_event`, cambiar a `PRIMARY KEY (id)` y agregar `CONSTRAINT uk_notification_event_client_event UNIQUE (client_id, event_id)` (design, decisión 1). No tocar `03-seed-notification-events.sql`. Verificar con `docker compose down -v && docker compose up -d mariadb`: `SELECT id, event_id FROM notification_event ORDER BY id` devuelve UUIDv7 para todos los eventos del seed, e insertar a mano un `event_id` repetido para el mismo `client_id` falla con duplicado.
- [x] 1.2 En `MariaDb.ageEvent` (tests del notificator), filtrar también por `client_id`, para que envejecer una fila nunca afecte a otro cliente con el mismo `event_id`. `MariaDb.deleteEvent` sigue borrando por `event_id`: los ids de test son únicos por corrida, así que la limpieza debe llevarse las filas de todos los clientes del test. Verificar que compila con `mvn -q test-compile` en `notificator`.

## 2. Notificator: identidad `(client_id, event_id)` en el repositorio

- [x] 2.1 En `JdbcNotificationEventRepository`, cambiar el fragmento `CURRENT`, `CLAIM_REPLAY` y el `SELECT` por id para filtrar `client_id = :clientId AND event_id = :eventId`. En el port `NotificationEventRepository`, agregar `clientId` a `claimAttempt`, `findById`, `markDelivered`, `recordAttempt`, `refreshIfStale` y `failIfStale`. Agregar `id` a `NotificationEvent` y a su `ROW_MAPPER`. Verificar con `JdbcNotificationEventRepositoryIntegrationTest` (`IT_DB_URL` definido) con un test nuevo por método de escritura: dos filas con el mismo `event_id` y distinto `client_id`, donde la escritura sobre una no cambia la otra.
- [x] 2.2 Cambiar `insertClaim` a `Optional<String>` con `INSERT ... RETURNING id`: vacío ante `DuplicateKeyException` (design, decisión 3). Verificar en el test de integración que devuelve un UUID versión 7 (`UUID.fromString(id).version() == 7`), que el duplicado del mismo cliente devuelve vacío, y que el mismo `event_id` de otro cliente crea una segunda fila con otro id.

## 3. Notificator: entrega y webhook

- [x] 3.1 Actualizar los callers de los métodos que ahora reciben `clientId`: `DeliveryService` (`retry` usa el `clientId` del `DeliveryRef`, y `deliver` y `complete` usan el de `TransactionEvent`), `ExhaustedEventService` y `StaleDeliveryRecoveryService`. Ajustar `TransactionEventListener` para pasar `ref.clientId()` a `retry`. Verificar con `mvn test` en `notificator`: `DeliveryServiceTest`, `ExhaustedEventServiceTest`, `StaleDeliveryRecoveryServiceTest` y `TransactionEventListenerTest` comprueban con Mockito que cada llamada al repositorio lleva el `client_id` correcto.
- [x] 3.2 Hacer que `DeliveryService.deliver` reciba el `notification_event_id`: en `first` lo toma de `insertClaim`, y en `retry` y `replay` del `id` de la fila que ya lee. Agregar `notification_event_id` a los logs "Event delivered", "Event failed" y "Replay started" (design, decisión 6). Verificar en `DeliveryServiceTest` que el id que llega a `WebhookClient.post` es el de `insertClaim` en el primer intento y el de la fila en el reintento y en el replay.
- [x] 3.3 Cambiar el port `WebhookClient.post` a `(url, notificationEventId, event, attempt)`. En `RestClientWebhookClient`, serializar un record `WebhookPayload` con el cuerpo `{notification_event_id, event_id, event_type, client_id, content, created_at}` y agregar el header `X-Notification-Event-Id`, conservando `X-Event-Id` (design, decisión 4). Verificar en `RestClientWebhookClientTest` que el cuerpo tiene los seis campos y que la petición lleva los tres headers con los valores esperados.

## 4. Manager: búsqueda por `id` y representación

- [x] 4.1 En `JdbcNotificationEventRepository` del manager, leer `id` en `SELECT`, cambiar `FIND_BY_ID` a `WHERE id = :id AND client_id = :clientId`, y devolver `Optional.empty()` sin consultar si el valor no tiene 36 caracteres o `UUID.fromString` lo rechaza; si es válido, consultar con la forma normalizada (design, decisión 5). Agregar `id` a `NotificationEvent`. Verificar con un test unitario de la validación: `EVT001`, `1-1-1-1-1` y texto vacío se rechazan, y un UUID en mayúsculas se normaliza.
- [x] 4.2 Agregar `notificationEventId` a `NotificationEventResponse`. Verificar en `NotificationEventControllerTest` que el listado y el detalle devuelven `notification_event_id` y `event_id`, El 404 de `GET /notification_events/EVT001` no se puede probar ahí, porque el controller test mockea el caso de uso. Lo cubren `JdbcNotificationEventRepositoryTest` (un valor que no es UUID da vacío sin consultar) más el 404 de not-found existente, y se comprueba de punta a punta en 6.1.
- [x] 4.3 En `NotificationEventReplayService`, publicar `ReplayRequested` con el `eventId` de la fila en lugar del valor del path. Loguear el valor del path como `notification_event_id` y el de la fila como `event_id` en "Replay requested", "Replay not allowed" y "Replay already in progress". Hacer lo mismo en `NotificationEventQueryService.get` ("Found notification event"). Verificar en `NotificationEventReplayServiceTest` que, para una fila con `id` `X` y `event_id` `EVT003`, el pedido publicado tiene `event_id` `EVT003`, El 404 de `POST /notification_events/EVT003/replay` se comprueba en 6.1, por la misma razón que en 4.2. Correr `mvn test` en `manager`.

## 5. Documentación y contexto

- [x] 5.1 En `README.md`, describir el claim del primer intento como `INSERT` sobre `(client_id, event_id)` y explicar que la API y el webhook exponen `notification_event_id` y `event_id`. Agregar `notification_event_id` como campo en el ejemplo de consulta de Loki. En `docs/flujo-notificator.mmd`, cambiar "(PK event_id)" por "(UNIQUE client_id, event_id)". Verificar releyendo esas secciones.
- [x] 5.2 En el `context` de `openspec/config.yaml`, reemplazar "Idempotente por event_id ... INSERT sobre la PK" por la identidad `(client_id, event_id)`, y agregar que el PK es `id` UUIDv7 expuesto como `notification_event_id`. Verificar con `openspec context --json` que el YAML sigue parseando.

## 6. Verificación integrada

- [x] 6.1 Con `docker compose down -v && docker compose up --build`:
  - `GET /notification_events` con `X-Client-Id: CLIENT002` devuelve `notification_event_id` y `event_id`.
  - `POST /notification_events/<id de EVT003>/replay` responde 202, y en kafka-ui el pedido tiene key `EVT003` y el mismo valor que antes.
  - El webhook-mock registra la entrega del replay. Con un `kafka-console-producer.sh` en `transactions.events.v1`, publicar el mismo `event_id` para `CLIENT001` y `CLIENT002`: se crean dos filas con ids distintos y cada cliente recibe su POST.
