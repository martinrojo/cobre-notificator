# Design

## Context

El manager hoy solo tiene `PingController` y la estructura hexagonal (`domain`, `application`, `infrastructure.adapter.in/out`) bajo `com.cobre.manager`, con Spring Boot 4.1.1 y sin conexión a la DB. La tabla `notification_event` ya existe (`notificator/db/01-schema.sql`) con índices `(client_id, created_at)` y `(client_id, delivery_status, created_at)`, y el usuario `manager` solo tiene SELECT sobre ella (`02-users.sh`). Requisitos: ver specs `client-identification` y `notification-event-query`.

## Goals / Non-Goals

**Goals:**
- Primer flujo completo de punta a punta en hexagonal: web → use case → port out → JDBC.
- Un único punto de identificación del cliente, que después se reemplaza por el JWT sin tocar los controllers.

**Non-Goals:**
- Escribir en la DB desde el manager (no tiene permisos).
- Paginación (va en un cambio aparte).

## Decisions

### Modelos por capa y mapeos con `from(...)`
Todos son records.
- **Dominio** (`domain.model`): `NotificationEvent(eventId, clientId, eventType, content, deliveryStatus, createdAt, deliveryDate)`, `DeliveryStatus` (enum de los 5 estados) y `NotificationEventFilter(clientId, from, to, deliveryStatus)`, que valida en su constructor compacto que `from <= to`. Para `deliveryDate` se usa `last_attempt_at`.
- **DTOs web** (`infrastructure.adapter.in.web.dto`):
  - Entrada: `NotificationEventsRequest(String from, String to, DeliveryStatus deliveryStatus)`, bindeado desde los query params (`@BindParam("delivery_status")`). `delivery_status` se mapea directo al enum de dominio durante el binding. `from` y `to` siguen como String y se parsean en `toFilter`, para que el mensaje de error nombre el parámetro y el formato esperado.
  - Conversión a enum: `DeliveryStatusConverter implements Converter<String, DeliveryStatus>` (`@Component` en el adapter web, que Spring Boot registra solo en MVC). Hace `trim()` + `toUpperCase(Locale.ROOT)` + `valueOf`. Para eso no alcanza el converter por defecto de Spring, que distingue mayúsculas. Un valor desconocido falla en el binding con `MethodArgumentNotValidException`, que el advice traduce a 400 con el mensaje de `delivery_status` inválido y la lista de valores permitidos (`DeliveryStatus.values()`).
  - Salida: `NotificationEventResponse(...)`, con `static from(NotificationEvent)`.
- **Mapeo de entrada:** el dominio no puede depender de un DTO de infraestructura. Por eso la conversión DTO → dominio vive en el DTO de entrada como `NotificationEventFilter toFilter(String clientId)`, que parsea y valida. Esta es la única excepción a la regla de `from(...)`; la alternativa (`NotificationEventFilter.from(request)`) rompe la regla de dependencias de la arquitectura hexagonal.
- **Fila → dominio:** no hay clase intermedia. El adapter JDBC define un `RowMapper<NotificationEvent>` como lambda/constante privada que construye el record de dominio.

### Ports
- In: `ListNotificationEventsUseCase { List<NotificationEvent> list(NotificationEventFilter) }`, implementado por `NotificationEventQueryService` (`application`).
- Out: `NotificationEventRepository { List<NotificationEvent> findAll(NotificationEventFilter) }`, implementado por `JdbcNotificationEventRepository` (`infrastructure.adapter.out.persistence`).

### SQL estático con filtros opcionales
```sql
SELECT event_id, client_id, event_type, content, delivery_status, created_at, last_attempt_at
FROM notification_event
WHERE client_id = :clientId
  AND (:from IS NULL OR created_at >= :from)
  AND (:to IS NULL OR created_at <= :to)
  AND (:status IS NULL OR delivery_status = :status)
ORDER BY created_at DESC
```
Se ejecuta con `JdbcClient` y parámetros nombrados. El driver de MariaDB prepara las sentencias del lado del cliente por defecto, así que los `NULL` llegan como literales y el optimizador descarta las condiciones vacías y usa los índices existentes.
Alternativa descartada: armar el `WHERE` dinámicamente concatenando strings. Más código y más superficie para errores de inyección (OWASP A03, Injection).

### Fechas siempre en UTC
`DATETIME(3)` no guarda zona horaria. El row mapper lee `LocalDateTime` y lo convierte con `toInstant(ZoneOffset.UTC)`, y los parámetros `from`/`to` se bindean como `LocalDateTime` en UTC. Así el resultado no depende de la zona horaria de la JVM ni de la conexión.

### Identificación del cliente: filtro protegido por defecto + holder request-scoped
- `ClientIdentificationFilter extends OncePerRequestFilter`, en `infrastructure.adapter.in.web.security`, con orden por defecto, que corre después del `RequestContextFilter` de Spring.
- **Protegido por defecto:** aplica a todos los requests, salvo los de la lista de rutas públicas (`PUBLIC_PATHS = Set.of("/ping")`, vía `shouldNotFilter`). Un endpoint nuevo nace protegido sin que nadie tenga que acordarse de nada.
- **Header ausente o en blanco:** lanza `UnauthenticatedClientException.missingClientId()` y la delega al `HandlerExceptionResolver` de Spring (inyectado con `@Qualifier("handlerExceptionResolver")`). Así el 401 pasa por el mismo `@RestControllerAdvice` y sale como `ProblemDetail`, igual que el resto de los errores, sin escribir JSON a mano en el filtro. La cadena no continúa, así que no hay binding, validación ni 404/405 previos al 401.
- **Header válido:**
  - setea `ClientContext.clientId`;
  - hace `MDC.put("client_id", ...)`, para que todos los logs del request lleven el campo `client_id` en el JSON;
  - llama a `chain.doFilter` y limpia el MDC en `finally`.
- `ClientContext`: bean `@RequestScope` que el controller recibe por constructor (es un proxy, así que es seguro en un singleton) y que expone `clientId()`. Es la única clase mutable: se setea una sola vez por request, desde el filtro.
- **Reemplazo por JWT:** el JWT se va a validar tanto en el gateway como en el manager (defensa en profundidad). Cuando llegue, este filtro se reemplaza por la validación del JWT (previsiblemente Spring Security como resource server, que también funciona como cadena de filtros). El client_id va a salir de un claim y el resto (controller, `ClientContext`, advice) no cambia.
- **Alternativas descartadas:**
  - Anotación + aspecto: la protección es opt-in (un endpoint sin anotación queda abierto, OWASP A01), corre después del binding (un request sin credenciales puede recibir 400 o 404 en vez de 401) y no encaja con Spring Security.
  - `HandlerInterceptor`: mismo problema de opt-in, y deja los 404/405 sin proteger.

### Errores con `ProblemDetail` (RFC 9457)
Un `@RestControllerAdvice` traduce:
- `UnauthenticatedClientException` → 401.
- `InvalidParameterException` (con factories `invalidDate(param, value)` e `invalidRange()`) → 400.
- `MethodArgumentNotValidException` por un error de binding de `delivery_status` → 400, con mensaje específico y valores permitidos. Nunca se devuelve el mensaje crudo de conversión de Spring.

El cuerpo es `ProblemDetail`, con `detail` como mensaje específico. Los errores inesperados quedan con el manejo por defecto de Spring (500, sin stacktrace en la respuesta). Se loguean en ERROR, sin `content` ni headers.

### JSON en snake_case
`spring.jackson.property-naming-strategy: SNAKE_CASE`, para que la API use el mismo formato que `notification_events.json` (`event_id`, `delivery_status`) sin anotar cada campo.

### Datasource
`spring-boot-starter-jdbc` y `org.mariadb.jdbc:mariadb-java-client` (versión gestionada por Boot). En `application.yml`:
- `url: jdbc:mariadb://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME}`
- `username: ${DB_USER}`
- `password: ${DB_PASSWORD}`

No hay credenciales por defecto. En el compose, el servicio `manager` mapea `DB_USER=${MANAGER_DB_USER}`, `DB_PASSWORD=${MANAGER_DB_PASSWORD}`, `DB_NAME=${MARIADB_DATABASE}` y `DB_HOST=mariadb`, con `depends_on: mariadb: condition: service_healthy`.

### Seed
`notificator/db/03-seed-notification-events.sql` inserta los 10 eventos de `notification_events.json`:
- `completed` → `DELIVERED` y `failed` → `FAILED`.
- `created_at = last_attempt_at = delivery_date`.
- `attempts` = 1 para los entregados y 4 para los fallidos.

### Logs
El endpoint no loguea nada en INFO (no es una transición de estado). En DEBUG se loguean el filtro aplicado (client_id, from, to, status) y la cantidad de resultados, nunca `content`.

## Risks / Trade-offs

- [El repositorio JDBC no tiene test automatizado, porque Testcontainers y H2 están fuera del stack] → Los tests unitarios cubren dominio, servicio, controller y filtro. El SQL se verifica en la tarea de integración contra MariaDB del compose con `curl` y los escenarios de la spec. Si se quiere un test automático del SQL, sumar Testcontainers en un cambio aparte.
- [Sin paginación, un cliente con muchos eventos devuelve todo] → Aceptado temporalmente; la paginación es el próximo cambio.
- [`X-Client-Id` es confiable solo si el manager no es alcanzable sin pasar por el gateway (OWASP A01, Broken Access Control)] → Hoy el manager expone 9290 en local. Se resuelve cuando entre el JWT: lo validan el gateway y el manager, así que el manager deja de confiar en un header.
- [`ClientContext` es mutable] → Lo setea únicamente el filtro, una vez por request, y vive solo durante ese request.
- [Una ruta pública mal agregada a `PUBLIC_PATHS` desprotege un endpoint] → La lista es exacta (sin comodines) y el test del filtro verifica que solo `/ping` pasa sin header.
- [La delegación al `HandlerExceptionResolver` depende de que el advice maneje `UnauthenticatedClientException`] → El test del controller verifica el 401 con cuerpo `ProblemDetail` a través de la cadena real de filtros de `@WebMvcTest`.
