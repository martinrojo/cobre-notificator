# Tasks

## 1. Modelo de resultado

- [x] 1.1 Agregar `WebhookResult.CircuitOpen` (record sin campos, javadoc: rechazado sin POST) y clasificarlo como `RETRYABLE` en `DeliveryOutcome.of`; verificar con un caso nuevo en `DeliveryOutcomeTest`
- [x] 1.2 Agregar `WebhookResult.isUnavailable()` según la tabla de la decisión 2b (sí: `TransportError`, 429, 502, 503, 504; no: el resto y `CircuitOpen`); verificar con un test parametrizado por código (200, 302, 400, 404, 408, 429, 500, 501, 502, 503, 504, 505), `TransportError` y `CircuitOpen`
- [x] 1.3 En `DeliveryService.toRecord`, mapear `CircuitOpen` a `AttemptRecord(now, url, null, "Circuit open")`; verificar con un test en `DeliveryServiceTest` donde el webhook mockeado devuelve `CircuitOpen`: el evento se registra `RETRYING` con `last_error="Circuit open"`, `last_http_status` nulo, y se lanza `RetryableDeliveryException`

## 2. Circuit breaker en el adapter HTTP

- [x] 2.1 Agregar `resilience4j.circuitbreaker.configs.default` a `notificator/src/main/resources/application.yml` con los valores de design.md (decisión 5), `wait-duration-in-open-state: ${CIRCUIT_OPEN_WAIT:30s}` y un comentario que diga que debe ser menor que `notificator.delivery.retry.delay`; verificar que la app arranca con `mvn test` (`NotificatorApplicationTest`)
- [x] 2.2 Inyectar `CircuitBreakerRegistry` en `RestClientWebhookClient` y aplicar el flujo de la decisión 2: `tryAcquirePermission`, `onSuccess`/`onError` según `isUnavailable()`, `releasePermission()` y relanzar ante una excepción inesperada; para `CircuitOpen` no registrar el timer de latencia, y sí el contador de intentos con `status="circuit_open"`. Verificar en `RestClientWebhookClientTest` (registry real con ventana chica):
  - 503 repetidos abren el circuito y el siguiente `post` devuelve `CircuitOpen` sin llegar al servidor;
  - read timeouts repetidos (`/slow`) abren el circuito;
  - 500 repetidos no lo abren y cada `post` llega al servidor;
  - 404 repetidos no lo abren;
  - con el circuito de `CLIENT002` abierto, un `post` de `CLIENT001` llega al servidor;
  - pasada la espera, un 204 en half-open cierra el circuito;
  - en half-open, una excepción inesperada en `send` devuelve el permiso sin contar como fallo: la siguiente llamada obtiene permiso y llega al servidor;
  - `CircuitOpen` incrementa `notificator.webhook.attempts{status="circuit_open"}` y no toca `notificator.webhook.latency`
- [x] 2.3 Exponer `CIRCUIT_OPEN_WAIT` en el bloque `environment` del notificator en `docker-compose.yml`, junto a `WEBHOOK_*`; verificar con `docker compose config`

## 3. Observabilidad

- [x] 3.1 Registrar el log INFO `"Circuit state changed"` (`client_id`, `from`, `to`) suscribiéndose a `onEntryAdded` + `onStateTransition` del registry (decisión 6); verificar con un test que fuerza la transición CLOSED→OPEN de un breaker del registry y captura el log
- [x] 3.2 Verificar que `/actuator/prometheus` expone `resilience4j_circuitbreaker_state` con `name=<client_id>` después de una llamada (test de arranque o a mano con `docker compose up` + `curl`); si el binder no se activa solo, registrarlo explícitamente
- [x] 3.3 Agregar el panel "Estado del circuito" a `observability/grafana/dashboards/cliente.json` sobre `resilience4j_circuitbreaker_state{name="$client"} == 1`; verificar que el JSON es válido (`python3 -m json.tool`) y que el panel se ve en Grafana con el webhook-mock respondiendo 503 para `CLIENT002`

## 4. Documentación e integración

- [x] 4.1 Documentar en `README.md`, en la sección del webhook-mock, cómo ver abrirse y cerrarse el circuito de `CLIENT002` (503 → circuito abierto en el dashboard de cliente → volver a 200 → cerrado) y la variable `CIRCUIT_OPEN_WAIT`; verificar siguiendo los pasos con `docker compose up`
- [x] 4.2 Prueba de integración manual del aislamiento: con el webhook-mock en 503 para `CLIENT002`, `delay_ms=1000` para `CLIENT003` (supera el read-timeout de 500 ms) y 200 para `CLIENT001`, generar tráfico con el simulador; verificar que se abren los circuitos de `CLIENT002` y `CLIENT003`, que todos los eventos de `CLIENT001` quedan `DELIVERED`, que los de `CLIENT002` muestran `last_error="Circuit open"` en `notification_event` (el manager no expone `last_error`), y que el lag de la partición compartida no crece
- [x] 4.3 `mvn test` en `notificator/` en verde, incluido el check de `fmt-maven-plugin`

## Workflow follow-up

- Archivar el change con `/opsx:archive` después de revisar, para aplicar el delta a `openspec/specs/notification-delivery/spec.md`.
