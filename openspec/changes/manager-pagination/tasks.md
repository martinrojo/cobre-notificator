# Tasks

## 1. Dominio y caso de uso

- [x] 1.1 Agregar `PageCursor(createdAt, id, filter)` y `NotificationEventPage(events, next)` en `domain/model`, y los factories `invalidCursor()`, `cursorFilterMismatch()`, `invalidSize(max)` e `invalidSizeFormat()` en `InvalidParameterException`. Verificar que compila con `mvn -pl manager compile`
- [x] 1.2 Cambiar `ListNotificationEventsUseCase.list` a `(filter, cursor, size) -> NotificationEventPage` y `NotificationEventRepository.findAll` a `(filter, after, limit)` (design §6). Verificar que compila
- [x] 1.3 En `NotificationEventQueryService`: inyectar `manager.query.default-page-size` y `manager.query.max-page-size` por `@Value`, aplicar el valor por defecto, validar `1..max`, rechazar un cursor de otro cliente (`'cursor' is invalid`) o con otros filtros (`'cursor' does not match the current filters`) sin llamar al repositorio, pedir `size + 1`, recortar y armar `next` con la última fila. Agregar `size` y `has_cursor` al log debug existente. Agregar las properties a `application.yml` (20 / 100, sobrescribibles por env). Verificar con `NotificationEventQueryServiceTest`: default, máximo aceptado, size 0 y 101 dan 400 sin consultar, cursor ajeno, filtros distintos, filtros equivalentes (otra zona horaria) aceptados, página exacta con `next` nulo y página con `size + 1` filas con `next` en la fila `size`

## 2. Persistencia

- [x] 2.1 En `JdbcNotificationEventRepository.findAll`: `ORDER BY created_at DESC, id DESC`, predicado keyset expandido con `afterCreatedAt` / `afterId` nulos en la primera página, y `LIMIT :limit` (design §1). `client_id` y los filtros siempre salen de `filter`. Verificar con `mvn -pl manager test` (tests unitarios existentes en verde). El SQL se prueba de punta a punta en la task 4.2

## 3. Adaptador web

- [x] 3.1 Agregar `CursorCodec` en `infrastructure/adapter/in/web`: base64url sin padding de JSON con el `JsonMapper` inyectado. Toda falla de decodificación lanza `invalidCursor()`. Verificar con `CursorCodecTest`: ida y vuelta conserva posición, cliente y filtros (incluidos nulos); `not-a-cursor`, base64 válido con JSON inválido y JSON sin campos dan `'cursor' is invalid`
- [x] 3.2 En `NotificationEventsRequest`, agregar `size` (String, se parsea a `Integer`; si no es numérico lanza `invalidSizeFormat()`) y `cursor` (String, se decodifica con el codec). Verificar con `NotificationEventsRequestTest`: `size=abc` da 400 nombrando `size`, y `size` vacío o ausente da `null`
- [x] 3.3 Agregar `NotificationEventPageResponse(data, nextCursor)` y hacer que `NotificationEventController.list` devuelva el envelope, codificando `next` con el codec. Verificar con `NotificationEventControllerTest`: el JSON tiene `data` y `next_cursor` (`null` presente en la última página), con `size=101`, `size=abc` y `cursor=not-a-cursor` responde 400 con `detail` que nombra el param, y el `next_cursor` devuelto se acepta en el siguiente request

## 4. Documentación y verificación integral

- [x] 4.1 Actualizar el README: el ejemplo de `curl` del listado muestra `data` / `next_cursor`, y se agrega uno que sigue `next_cursor` con `size`. Documentar `MANAGER_QUERY_*` en `.env.example` si se exponen por env. Verificar que los comandos del README corren tal cual contra `docker compose up`
- [x] 4.2 Con `docker compose up` y el seed: recorrer `GET /notification_events?size=2` siguiendo `next_cursor` hasta `null` y comprobar que no hay repetidos ni faltantes contra el listado con `size=100`. Repetir con `delivery_status=FAILED`. Comprobar que cambiar el filtro con un cursor vigente da 400 y que el cursor de `CLIENT001` usado con el token de `CLIENT002` da 400. Correr `EXPLAIN` de la query en MariaDB y confirmar que usa `idx_event_client_created` / `idx_event_client_status_created` sin `filesort`

## Workflow follow-up

- Archivar `notification-event-own-id` antes que este cambio, porque el delta de `notification-event-query` parte de su versión del requisito de listado.
- Archivar este cambio cuando se cumpla la revisión.
