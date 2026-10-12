# Design

## Context

Ver proposal.md (Why). Lo que define el enfoque:

- `DeliveryService` reclama el intento (`INSERT` o `UPDATE attempts = n`) **antes** del POST, y `attempts` nunca baja dentro de una generación. Un intento que no llega a hacer POST ya está consumido.
- `WebhookClient.post()` nunca lanza: devuelve el sealed `WebhookResult` (`Responded` | `TransportError`), y `DeliveryOutcome.of()` lo clasifica en `DELIVERED`, `RETRYABLE` o `PERMANENT`. Un `RETRYABLE` registra `RETRYING` y lanza `RetryableDeliveryException`, que `@RetryableTopic` manda al siguiente `notificator.retry.v1-*`.
- El cliente HTTP no reintenta: cada intento es un único POST. Los reintentos son de Kafka (`@RetryableTopic`): cada uno es un mensaje nuevo en un tópico de retry que, al consumirse, reclama el intento siguiente y hace su propio POST, a los 60 s, 300 s y 1500 s del fallo. No bloquean el tópico de origen: el mensaje fallido se publica en el siguiente `notificator.retry.v1-*` y el offset se confirma. En los tópicos de retry la espera es una pausa de la partición hasta el vencimiento, no un hilo ocupado; como hay un tópico por delay, la cabeza siempre es la próxima en vencer. Pero cada POST (en el tópico principal, en el de replay o en cualquier tópico de retry) es síncrono y ocupa el hilo de su partición, compartida por varios clientes, hasta connect + read (250 ms + 500 ms, igual en `application.yml` y `docker-compose.yml`). Ese es el acoplamiento que corta el breaker, en todos los tópicos: con un cliente caído, la mayor parte de su tráfico está en los de retry. Los errores transitorios de la base sí se reintentan bloqueando, en el mismo offset; este cambio no los toca.
- `resilience4j-spring-boot4` 2.4.0 ya está en el `pom.xml` y trae `resilience4j-micrometer`: el `CircuitBreakerRegistry` autoconfigurado publica `resilience4j_circuitbreaker_state` para cada breaker que se crea en él.

## Goals / Non-Goals

**Goals:**
- Que un cliente caído deje de ocupar el hilo de la partición compartida después de pocas llamadas.
- Que el dominio y `DeliveryService` sigan sin depender de Resilience4j.

**Non-Goals:**
- Cambiar el modelo de intentos, generaciones o fencing.
- Que el breaker proteja el webhook del cliente con límites de concurrencia o de tasa.

## Decisions

### 1. Breaker en el adapter HTTP, uno por `client_id` desde el registry

`RestClientWebhookClient` recibe el `CircuitBreakerRegistry` y en cada `post()` usa `registry.circuitBreaker(event.clientId())`. Los nombres no declarados en `instances` toman `resilience4j.circuitbreaker.configs.default`, así que no hay nada que registrar por cliente. Los clientes son finitos (tabla `subscription`), por lo que el número de breakers y de series de la métrica está acotado.

*Alternativas*: `@CircuitBreaker` por anotación (un nombre fijo, sería un breaker global: descartado); un decorador `WebhookClient` aparte (una clase más con una sola implementación para algo que son ~10 líneas en el adapter: descartado); un breaker en `DeliveryService` (mete Resilience4j en la capa de aplicación: descartado).

### 2. Registro manual de resultados

Como `post()` nunca lanza, no se usa `executeSupplier`. El flujo es:

```
body = serialize(event)
if !cb.tryAcquirePermission()  -> return CircuitOpen          (sin POST, sin timer)
start = nanoTime
try   result = send(...)
catch RuntimeException e -> cb.releasePermission(); throw    (devuelve el permiso sin contar)
if result.isUnavailable() -> cb.onError(elapsed, WebhookFailure)
else                      -> cb.onSuccess(elapsed)
```

`WebhookFailure` es una excepción privada sin stack trace (`writableStackTrace=false`) que solo sirve para alimentar `onError`.

Una excepción inesperada en `send` (cualquier cosa distinta de `ResourceAccessException`, que ya es `TransportError`) es un error nuestro, no evidencia de que el cliente no esté disponible: se devuelve el permiso con `releasePermission()`, sin registrar éxito ni fallo, y se relanza. Sin esto, en half-open el permiso quedaría "en vuelo" para siempre y el breaker esperaría un resultado que nunca llega.

### 2b. Qué abre el circuito: clasificación propia, distinta de los reintentos

`DeliveryOutcome` responde "¿puede funcionar si se reintenta?". El breaker responde otra pregunta: "¿el cliente está no disponible?". Se modela como método puro del dominio `WebhookResult.isUnavailable()`, junto a `DeliveryOutcome`:

| Resultado | `DeliveryOutcome` | `isUnavailable()` |
|---|---|---|
| 2xx | DELIVERED | no |
| 3xx, 4xx salvo 408/429 | PERMANENT | no |
| 408 | RETRYABLE | no |
| 429 | RETRYABLE | sí |
| 500, 501, 505 y demás 5xx | RETRYABLE | no |
| 502, 503, 504 | RETRYABLE | sí |
| `TransportError` (timeout de conexión o lectura, conexión rechazada, host sin resolver, I/O) | RETRYABLE | sí |
| `CircuitOpen` | RETRYABLE | no (nunca se registra en el breaker) |

Criterio: el breaker existe para dejar de ocupar el hilo de la partición y de cargar a un cliente que lo pide. Un 500 o un 408 son respuestas rápidas de un cliente vivo: se reintentan, pero no abren el circuito. Un timeout ocupa el hilo; 503, 429, 502 y 504 son el cliente (o su gateway) pidiendo que paremos.

*Alternativa*: reusar `DeliveryOutcome == RETRYABLE`. Más simple, pero un cliente con un bug que devuelve 500 en un tipo de evento abriría el circuito y frenaría también sus eventos sanos. Descartado.

*Alternativa*: `recordResult` con un `Predicate` declarado por nombre de clase en el YAML y `cb.onResult(...)`. Lleva la clasificación a un lugar menos visible y depende de una clase instanciada por reflexión. Descartado.

### 3. Nueva variante `WebhookResult.CircuitOpen`

`record CircuitOpen() implements WebhookResult`. Al ser sealed, el compilador obliga a manejarla en todos los `switch`:

| Lugar | Tratamiento |
|---|---|
| `DeliveryOutcome.of` | `RETRYABLE` |
| `DeliveryService.toRecord` | `AttemptRecord(now, url, null, "Circuit open")` |
| `RestClientWebhookClient.status` | `"circuit_open"` |
| timer `notificator.webhook.latency` | no se registra |
| contador `notificator.webhook.attempts` | se incrementa con `status="circuit_open"`, `outcome="retryable"` |

`DeliveryService` no necesita una rama nueva: el rechazo sigue el camino de `RETRYABLE` existente (registra `RETRYING`, log ERROR, lanza `RetryableDeliveryException`).

*Alternativa*: reusar `TransportError("CallNotPermittedException")`. No toca el modelo, pero `TransportError` significa "puede o no haber llegado al cliente", lo que es falso aquí, y pasaría por el timer con ~0 ms, bajando el p95 justo cuando el cliente está peor. Descartado.

### 4. El rechazo consume el intento

El claim ya ocurrió. Devolverlo exige bajar `attempts`, lo que rompe el fencing. Retener el mensaje (pausar o reintentar en el mismo offset hasta el half-open) bloquea la partición, que es justo el daño cruzado que se quiere evitar. El costo de consumir el intento es que un cliente caído más de ~31 min termina en `FAILED` con menos POST reales que hoy; el replay del manager ya cubre ese caso.

### 5. Configuración

En `application.yml`, bajo `resilience4j.circuitbreaker.configs.default`, sobreescribible por variables de entorno como el resto:

| Propiedad | Valor | Motivo |
|---|---|---|
| `sliding-window-type` | `COUNT_BASED` | El volumen por cliente es irregular; una ventana por tiempo con poco tráfico decide con muy pocas muestras |
| `sliding-window-size` | 20 | |
| `minimum-number-of-calls` | 5 | Acota el bloqueo antes de abrir a 5 × (connect + read): ~4 s con 250 ms + 500 ms |
| `failure-rate-threshold` | 50 | |
| `wait-duration-in-open-state` | `${CIRCUIT_OPEN_WAIT:30s}` | Menor que `retry.delay` (60 s), así cada ola de reintentos encuentra el half-open |
| `permitted-number-of-calls-in-half-open-state` | 2 | Pocas sondas: cada una puede bloquear el hilo un timeout |
| `max-wait-duration-in-half-open-state` | 10s | Red de seguridad: si un permiso se pierde por cualquier camino, el breaker vuelve a OPEN al vencer en lugar de quedar en half-open para siempre (el default 0 espera indefinidamente) |
| `automatic-transition-from-open-to-half-open-enabled` | `false` | Evita un timer por cliente; la transición ocurre con la siguiente llamada |
| `slow-call-*` | sin definir (defaults) | El read-timeout ya convierte una llamada lenta en fallo |

La regla `wait < retry.delay` no se valida en el arranque. Queda documentada junto a la propiedad en `application.yml`.

### 6. Log de transiciones

Un bean de configuración se suscribe a `registry.getEventPublisher().onEntryAdded(...)` y, para cada breaker nuevo, a `onStateTransition`, con log INFO `"Circuit state changed"` con `client_id` (el nombre del breaker), `from` y `to`. Así se cumple la regla de logs (INFO solo para transiciones relevantes) sin un log por cada rechazo.

### 7. Panel en Grafana

En `cliente.json`: un panel "Estado del circuito" sobre `resilience4j_circuitbreaker_state{name="$client"}`, que muestra el estado con valor 1. El panel existente "Intentos fallidos por código" ya muestra `status="circuit_open"` sin cambios.

## Risks / Trade-offs

- [Estado por instancia: con N instancias, cada una aprende por su cuenta y un cliente caído recibe hasta N × `minimum-number-of-calls` llamadas antes de quedar cortado en todas] → Aceptado. Sigue acotado, y compartir el estado agrega infraestructura y latencia en cada llamada.
- [Con la transición perezosa, la métrica muestra `open` hasta la siguiente llamada del cliente aunque ya haya pasado la espera] → Aceptado. El panel lo dice como "abierto o esperando prueba"; no afecta a la entrega.
- [Un cliente abierto con mucho volumen genera un log ERROR por evento rechazado] → Es la regla vigente para intentos que se reintentan. Si molesta en Loki, se baja a DEBUG en un cambio aparte; el INFO de transición ya da la señal.
- [Antes de abrir, el cliente caído sigue bloqueando la partición ~`minimum-number-of-calls` × timeout, y las sondas del half-open también] → Acotado por configuración. Eliminarlo del todo requiere hilos o particiones por cliente: fuera de alcance.
- [Un permiso adquirido que no se registra (excepción inesperada en `send`) dejaría el half-open esperando para siempre] → El `catch` de la decisión 2 devuelve el permiso con `releasePermission()` (no `onError`: un error nuestro no indica que el cliente esté caído) y relanza; además `max-wait-duration-in-half-open-state: 10s` saca al breaker del half-open si igual se pierde un permiso.
- [Un cliente que alterna caídas cortas puede quemar los 4 intentos en rechazos] → Aceptado, se recupera por replay.

## Migration Plan

Sin migración de datos. El despliegue empieza con todos los circuitos cerrados. Rollback: volver a la versión anterior; no queda estado persistido.
