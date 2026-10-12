# Proposal

## Why

El notificator entrega a webhooks de clientes que no existen en el entorno local, y la tabla `subscription` está vacía: todo evento termina en `NOT_SUBSCRIBED` y los caminos interesantes (reintentos, circuit breaker por cliente, DLT, `FAILED` → replay) no se pueden ejercitar ni mostrar en los dashboards. Hace falta un receptor de webhooks local cuyo comportamiento (código de respuesta y latencia) se pueda cambiar en caliente, por cliente, sin reiniciar nada.

## What Changes

- Nueva aplicación Java `webhook-mock` (Spring Boot web, sin DB ni Kafka) que expone `POST /webhooks/{client_id}` y responde con el código HTTP y la demora configurados para ese cliente.
- API de configuración en caliente: comportamiento por defecto y override por `client_id` (código de respuesta y demora en ms), con lectura y reset. Estado en memoria; se pierde al reiniciar.
- Chequeo de aislamiento: si el `client_id` del cuerpo no coincide con el del path, el mock lo registra como ERROR (sirve para evidenciar que un cliente nunca recibe eventos de otro).
- Script `notificator/db/04-seed-subscriptions.sql`: suscripciones activas para `CLIENT001`..`CLIENT008` × los 10 tipos de evento, apuntando a `http://webhook-mock:8090/webhooks/<client_id>`.
- Servicio `webhook-mock` en `docker-compose.yml` (arranca por defecto, puerto publicado solo en `127.0.0.1`).

Aplicaciones afectadas: ninguna de las existentes cambia su código (gateway, manager, notificator). Solo se agrega un script de seed en `notificator/db/` y la herramienta de desarrollo `webhook-mock`.

### Fuera de alcance

- Tasa de error aleatoria / respuestas probabilísticas (solo comportamiento determinista por cliente).
- Persistir la configuración o el historial de requests recibidos.
- Autenticación de la API de configuración (herramienta local, no expuesta).
- Firma/verificación de webhooks (el notificator hoy no firma).
- Métricas Prometheus propias: latencia y resultados ya los mide el notificator.
- Despliegue fuera del entorno local.

## Capabilities

### New Capabilities
- `webhook-mock`: receptor local de webhooks con respuesta y latencia configurables por cliente vía HTTP.

### Modified Capabilities
<!-- Ninguna: el contrato de entrega del notificator no cambia; el seed de suscripciones es dato local, no requisito. -->

## Impact

- Código nuevo: `webhook-mock/` (pom.xml, Dockerfile, .dockerignore, src/).
- `notificator/db/04-seed-subscriptions.sql`: 80 filas en `subscription`. Solo aplica en un volumen de MariaDB nuevo (`docker compose down -v`).
- `docker-compose.yml`: servicio `webhook-mock`; el notificator ya corre con `WEBHOOK_ALLOW_HTTP=true`.
- Con el seed, los eventos del `transaction-simulator` pasan de `NOT_SUBSCRIBED` a entregas reales (~50 req/s sobre el mock).
- Dependencias: solo del stack (Spring Boot 4 web). Sin dependencias nuevas.
