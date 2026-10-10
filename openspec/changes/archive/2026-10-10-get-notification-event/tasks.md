# Tasks

## 1. Dominio y aplicación

- [x] 1.1 Crear `NotificationEventNotFoundException` en `domain.exception` con `static of(String eventId)` y mensaje fijo `Notification event not found`; agregar `Optional<NotificationEvent> findById(String clientId, String eventId)` a `NotificationEventRepository`; crear `GetNotificationEventUseCase` (port in) e implementarlo en `NotificationEventQueryService`. Verificar con `NotificationEventQueryServiceTest` (Mockito): evento encontrado → se devuelve; `Optional.empty()` → `NotificationEventNotFoundException`; el repositorio recibe el client_id y el event_id tal cual

## 2. Adapter web

- [x] 2.1 Agregar `GET /notification_events/{notification_event_id}` a `NotificationEventController` (con inyección de `GetNotificationEventUseCase`, que responde `NotificationEventResponse.from(...)`) y el handler 404 en `ApiExceptionHandler`. Verificar con `NotificationEventControllerTest`: 200 con JSON snake_case; 404 `ProblemDetail` con `detail` `Notification event not found`; 401 sin header; el client_id del header y el id del path llegan al use case

## 3. Adapter de persistencia

- [x] 3.1 Implementar `findById` en `JdbcNotificationEventRepository` con `WHERE event_id = :eventId AND client_id = :clientId`, reusando `ROW_MAPPER`. Verificar que `mvn -f manager/pom.xml verify` pasa

## 4. Integración

- [x] 4.1 Reconstruir el manager (`docker compose up -d --build manager`) y ejecutar con `curl` los escenarios de la spec: `EVT001` con `CLIENT001` → 200; `EVT003` con `CLIENT002` → 200 `FAILED`; `EVT003` con `CLIENT001` → 404; `EVT999` → 404 con el mismo `title` y `detail`; sin header → 401. Verificar que en los logs no aparece `content`

## Workflow follow-up

- Archivar el cambio una vez revisado.
