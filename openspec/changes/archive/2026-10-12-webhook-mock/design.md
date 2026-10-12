# Design

## Context

Ver proposal.md - Why. El notificator hace `POST` a la `webhook_url` de `subscription` con el `TransactionEvent` en JSON snake_case y headers `X-Event-Id` y `X-Delivery-Attempt`; no sigue redirects, corta por `WEBHOOK_READ_TIMEOUT` (10 s en compose) y en compose ya corre con `WEBHOOK_ALLOW_HTTP=true`. `subscription` es por `(client_id, event_type)` y hoy no tiene seed. El `transaction-simulator` es el precedente de herramienta de desarrollo: app Spring Boot plana, sin hexagonal, mismo pom/Dockerfile que el resto.

## Goals / Non-Goals

**Goals:**
- Una app mínima (2–3 clases) que se pueda reconfigurar con `curl` mientras corre carga.
- Soportar ~50 req/s con demoras de hasta 60 s sin agotar threads.

**Non-Goals:**
- Arquitectura hexagonal: no hay dominio, solo un mapa de configuración.
- Historial consultable de requests: los logs DEBUG alcanzan.

## Decisions

### App Spring Boot web, módulo propio `webhook-mock/`
Mismo layout que `transaction-simulator` (parent Spring Boot 4.1.1, Java 25, `fmt-maven-plugin` check, `finalName app`, Dockerfile multi-stage). Dependencias: `spring-boot-starter-web`, `spring-boot-starter-validation` y `spring-boot-starter-test`. Puerto `8090`.
- Alternativa: `com.sun.net.httpserver` de la JDK sin Spring. Cero dependencias, pero pierde logging estructurado, binding JSON y validación, y rompe la convención del repo.
- Alternativa: WireMock. Dependencia nueva fuera del stack; su API de stubs es más de lo que hace falta para "status + delay por cliente".

### Virtual threads para la demora
`spring.threads.virtual.enabled: true` y la demora es un `Thread.sleep(delayMs)` en el handler. Con 50 req/s y 10 s de demora hay ~500 requests en vuelo; con virtual threads eso no agota el pool de Tomcat (200 por defecto).
- Alternativa: `DeferredResult` + scheduler. Igual de correcto, más código.

### Estado en memoria
Record `Behavior(int status, long delayMs)` validado con Bean Validation (`@Min(200) @Max(599)`, `@Min(0) @Max(60000)`, `@NotNull` en los wrappers). Un `volatile Behavior defaultBehavior` y un `ConcurrentHashMap<String, Behavior>` de overrides. La resolución es `overrides.getOrDefault(clientId, defaultBehavior)`. Errores de validación → `400` con `ProblemDetail` que nombra el campo (manejo nativo de Spring con `spring.mvc.problemdetails.enabled: true`).

### Endpoints
- `POST /webhooks/{client_id}`: lee del cuerpo solo `event_id` y `client_id` (record con `@JsonIgnoreProperties(ignoreUnknown = true)`; `content` nunca se mapea), compara con el path, duerme y responde el status con cuerpo vacío.
- `GET /config`, `PUT /config`, `PUT /config/clients/{client_id}`, `DELETE /config/clients/{client_id}`.
Rutas de config separadas de `/webhooks` para que un `client_id` nunca colisione con un nombre reservado como `default`.

### Seed de suscripciones
`notificator/db/04-seed-subscriptions.sql` con `INSERT` de 80 filas (8 clientes × 10 tipos) generadas con un `CROSS JOIN` de dos tablas derivadas (`VALUES`/`UNION ALL`), así el script queda en ~20 líneas. Corre como root en el init de MariaDB: no cambia los permisos de ninguna app (el notificator sigue con solo `SELECT` sobre `subscription`).

### docker-compose
Servicio `webhook-mock` sin profile (las suscripciones del seed apuntan a él siempre), `ports: "127.0.0.1:8090:8090"` para poder configurarlo desde el host. El notificator no necesita `depends_on`: si el mock no está, la entrega falla por conexión y se reintenta, que es el comportamiento real.

### Logs
JSON estructurado como el resto. INFO en cada cambio de configuración (defecto o cliente, status, delay). DEBUG por request recibido (`client_id`, `event_id`, `X-Delivery-Attempt`, status devuelto). ERROR en entrega cruzada. Nunca `content` ni el cuerpo crudo.

### Seguridad (OWASP)
- A01 Broken Access Control: el chequeo de entrega cruzada sirve como evidencia en runtime del aislamiento por `client_id` que exige el caso.
- A05 Security Misconfiguration: la API de configuración no tiene auth; se mitiga publicando el puerto solo en `127.0.0.1` y documentando que es exclusivamente local. Las URLs `http://` del seed solo son aceptadas porque el compose habilita `WEBHOOK_ALLOW_HTTP`; en producción siguen exigiéndose `https`.
- A10 SSRF: sin cambios; el notificator sigue sin seguir redirects.

## Risks / Trade-offs

- [El seed solo corre con volumen nuevo] → documentar `docker compose down -v` en el README.
- [Configuración se pierde al reiniciar el mock] → aceptado: el estado inicial (200, sin demora) es el caso feliz y es fácil de reconfigurar.
- [Demoras largas con carga alta] → hasta ~3000 requests en vuelo con 60 s; virtual threads lo soportan, el límite real es el read-timeout del notificator.
- [Mock caído] → los eventos van a `RETRYING` y eventualmente `FAILED`; es útil para probar el replay.
