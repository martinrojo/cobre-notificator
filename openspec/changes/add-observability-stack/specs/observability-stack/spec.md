# Spec Delta

## Purpose

Recolecta las métricas y los logs del gateway, el manager y el notificator en el entorno local de docker compose y los deja consultables en Grafana sin configuración manual.

## ADDED Requirements

### Requirement: Stack de observabilidad en docker compose
`docker compose up` SHALL levantar Prometheus, Loki, Alloy y Grafana junto con las apps, sin profile ni pasos manuales. Las UIs de Grafana y Prometheus SHALL publicarse solo en `127.0.0.1`; Loki y Alloy no publican puertos.

#### Scenario: Stack levantado
- **GIVEN** un `.env` creado desde `.env.example`
- **WHEN** se ejecuta `docker compose up -d`
- **THEN** `http://localhost:3000` responde con el login de Grafana y `http://localhost:9090/-/ready` responde HTTP 200

#### Scenario: Puertos internos no expuestos
- **GIVEN** el stack levantado
- **WHEN** se listan los puertos publicados con `docker compose ps`
- **THEN** Loki (3100), Alloy y los puertos de actuator de las apps (9291, 9391) no aparecen publicados, y Grafana y Prometheus solo escuchan en `127.0.0.1`

### Requirement: Scrape de métricas de las apps
Prometheus SHALL scrapear `/actuator/prometheus` de `manager`, `notificator` y `gateway` por la red interna de compose, con el label `app` igual al nombre del servicio. Un target caído o inexistente SHALL figurar como `DOWN` sin impedir el scrape de los demás.

#### Scenario: Métricas del notificator disponibles
- **GIVEN** el stack levantado y un evento procesado por el notificator con estado final `DELIVERED`
- **WHEN** se consulta en Prometheus `notificator_deliveries_total{app="notificator",outcome="delivered"}`
- **THEN** la serie existe con valor mayor o igual a 1

#### Scenario: Métricas del manager disponibles
- **GIVEN** el stack levantado y un `POST /notification_events/{id}/replay` respondido por el manager
- **WHEN** se consulta en Prometheus `manager_replay_requests_total{app="manager"}`
- **THEN** la serie existe con valor mayor o igual a 1

#### Scenario: Gateway todavía no implementado
- **GIVEN** el servicio `gateway` no existe en compose
- **WHEN** se consulta `up{app="gateway"}` en Prometheus
- **THEN** el target figura `DOWN` y `up{app="manager"}` y `up{app="notificator"}` valen 1

### Requirement: Recolección de logs en Loki
Los logs JSON de stdout de las apps SHALL llegar a Loki con los labels `app` y `level` únicamente. `client_id` y `event_id` SHALL quedar como campos del JSON, nunca como labels.

#### Scenario: Log de entrega consultable
- **GIVEN** un evento del cliente `CLIENT001` que el notificator deja en `FAILED`
- **WHEN** se consulta en Grafana `{app="notificator"} | json | client_id="CLIENT001"`
- **THEN** aparece la línea del log de esa transición

#### Scenario: Sin labels por cliente
- **GIVEN** logs de eventos de `CLIENT001` y `CLIENT002` ingeridos en Loki
- **WHEN** se listan los labels disponibles en Loki
- **THEN** solo existen labels de infraestructura (`app`, `level` y los internos de Loki), y no existen `client_id` ni `event_id`

### Requirement: Lectura legible de logs
Los logs SHALL poder leerse como una línea por evento, sin JSON, tanto en el panel de logs de Grafana como en `docker compose logs`, sin cambiar el formato JSON que escriben las apps. Cada línea SHALL mostrar nivel, logger, mensaje, `event_id` y `client_id` cuando existan. Una excepción SHALL quedar en la misma línea que su evento, con los saltos de línea escapados.

#### Scenario: Log de entrega legible en Grafana
- **GIVEN** el notificator entrega un evento `EVT1` de `CLIENT001`
- **WHEN** se mira el panel de logs del dashboard
- **THEN** aparece la línea `INFO DeliveryService Event delivered event_id=EVT1 client_id=CLIENT001`

#### Scenario: Excepción en una sola línea
- **GIVEN** un log de nivel `ERROR` con una excepción adjunta (por ejemplo, un mensaje inválido enviado al DLT)
- **WHEN** se lee en el panel de logs o con `docker compose logs --no-log-prefix notificator | jq -Rr -f observability/logs.jq`
- **THEN** el evento ocupa una sola línea que termina con `exception=...`, con el stack trace con `\n` escapados, y la cantidad de líneas formateadas es igual a la de líneas JSON

#### Scenario: Error como texto y como excepción
- **GIVEN** un log con el key-value `error` como string (`Event failed`) y otro con una excepción en el objeto ECS `error`
- **WHEN** se formatean
- **THEN** el primero muestra `error=<motivo>` y el segundo `exception=<stack trace>`, sin fallar

### Requirement: Grafana provisionado como código
Grafana SHALL arrancar con los datasources Prometheus y Loki y con un dashboard de las apps cargados desde archivos del repo. La contraseña del admin SHALL venir de una variable de entorno, nunca del repo.

#### Scenario: Dashboard disponible al arrancar
- **GIVEN** un volumen de Grafana vacío
- **WHEN** se levanta el stack y se abre el dashboard provisionado
- **THEN** muestra, sin configuración manual, entregas por `outcome` y por `client_id`, mensajes en DLT, latencia p95 del webhook, replays del manager y requests HTTP por status, y un panel de logs de las apps

#### Scenario: Latencia p95 del webhook
- **GIVEN** entregas al webhook con resultado `DELIVERED` y `RETRYING`
- **WHEN** se mira el panel de latencia del webhook
- **THEN** muestra el p95 por `outcome`, calculado del histograma `notificator_webhook_latency_seconds_bucket`

#### Scenario: Credenciales de admin
- **GIVEN** `GRAFANA_ADMIN_PASSWORD` definido en `.env`
- **WHEN** se ingresa a Grafana con `admin` y esa contraseña
- **THEN** el login es exitoso, y el valor no aparece en ningún archivo commiteado salvo como ejemplo en `.env.example`
