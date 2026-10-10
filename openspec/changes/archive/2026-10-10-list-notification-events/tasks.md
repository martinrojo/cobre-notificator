# Tasks

## 1. Dependencias y configuración

- [x] 1.1 Agregar `spring-boot-starter-jdbc` y `mariadb-java-client` (runtime) a `manager/pom.xml`. Verificar con `mvn -f manager/pom.xml -q dependency:tree` que resuelven
- [x] 1.2 Configurar en `application.yml` el datasource con `${DB_HOST:localhost}`, `${DB_PORT:3306}`, `${DB_NAME}`, `${DB_USER}`, `${DB_PASSWORD}` y `spring.jackson.property-naming-strategy: SNAKE_CASE`. Verificar que no queda ninguna credencial literal en el archivo

## 2. Dominio y aplicación

- [x] 2.1 Crear los records `NotificationEvent` y `NotificationEventFilter` (este último valida `from <= to` en su constructor compacto) y el enum `DeliveryStatus` en `domain.model`, más `InvalidParameterException` con static factories. Verificar con `NotificationEventFilterTest` (rango válido, rango invertido → excepción, límites iguales permitidos)
- [x] 2.2 Crear los ports `ListNotificationEventsUseCase` (in) y `NotificationEventRepository` (out), y `NotificationEventQueryService` en `application`. Verificar con `NotificationEventQueryServiceTest` (Mockito) que delega el filtro tal cual al repositorio

## 3. Identificación del cliente

- [x] 3.1 Crear `ClientContext` (`@RequestScope`), `UnauthenticatedClientException.missingClientId()` y `ClientIdentificationFilter` (`OncePerRequestFilter`, `PUBLIC_PATHS = {"/ping"}`, delegación al `HandlerExceptionResolver`, `client_id` en MDC con limpieza en `finally`) en `infrastructure.adapter.in.web.security`. Verificar con `ClientIdentificationFilterTest` (Mockito + `MockHttpServletRequest`): header presente → `ClientContext` seteado, cadena continúa y MDC limpio al terminar; ausente, vacío o en blanco → resolver invocado y cadena cortada; `/ping` → no filtrado

## 4. Adapter web

- [x] 4.1 Crear `DeliveryStatusConverter` (`Converter<String, DeliveryStatus>`, sin distinguir mayúsculas) y los DTOs `NotificationEventsRequest` (`deliveryStatus` tipado como `DeliveryStatus`, con `toFilter(clientId)` que parsea fechas ISO-8601) y `NotificationEventResponse.from(NotificationEvent)`. Verificar con `DeliveryStatusConverterTest` (minúsculas y espacios aceptados, valor desconocido → excepción) y `NotificationEventsRequestTest` (fecha inválida, params nulos)
- [x] 4.2 Crear `NotificationEventController` (`GET /notification_events`, inyección por constructor de `ListNotificationEventsUseCase` y `ClientContext`) y el `@RestControllerAdvice` que mapea a `ProblemDetail` 401/400. Verificar con `NotificationEventControllerTest` (`@WebMvcTest` con el filtro): 200 con JSON snake_case, `[]` sin resultados, 401 `ProblemDetail` sin header o con header en blanco (también con params inválidos: 401 antes que 400), 401 sin header en una ruta inexistente, 400 por fecha, rango y `delivery_status` desconocido (mensaje con la lista de permitidos, no el error crudo de conversión), `delivery_status=delivered` aceptado, y que el client_id del header es el que llega al use case
- [x] 4.3 Verificar con el test existente que `GET /ping` sigue respondiendo 200 sin `X-Client-Id`

## 5. Adapter de persistencia

- [x] 5.1 Crear `JdbcNotificationEventRepository` en `infrastructure.adapter.out.persistence`, con `JdbcClient`, el SQL estático con filtros opcionales y el `RowMapper` privado (`LocalDateTime` ↔ `Instant` en UTC). Verificar que compila y que `mvn -f manager/pom.xml verify` pasa. El SQL se verifica en el grupo 6

## 6. Seed, compose e integración

- [x] 6.1 Crear `notificator/db/03-seed-notification-events.sql` con los 10 eventos de `notification_events.json` (`completed` → `DELIVERED`, `failed` → `FAILED`). Verificar con `docker compose down -v && docker compose up -d mariadb` y un `SELECT COUNT(*)` igual a 10
- [x] 6.2 Agregar al servicio `manager` del compose las variables `DB_HOST`, `DB_NAME`, `DB_USER` y `DB_PASSWORD` (mapeadas desde `.env`) y `depends_on` con `service_healthy`. Verificar con `docker compose up -d --build manager` que arranca sin errores de conexión
- [x] 6.3 Ejecutar con `curl` contra `localhost:9290` todos los escenarios de las specs `notification-event-query` y `client-identification` y verificar códigos y contenidos esperados, incluido que `CLIENT001` nunca ve `EVT003`. Verificar en los logs del manager que no aparece `content`

## Workflow follow-up

- Archivar el cambio una vez revisado.
