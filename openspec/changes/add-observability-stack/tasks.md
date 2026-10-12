# Tasks

## 1. Métricas

- [x] 1.1 Crear `observability/prometheus/prometheus.yml` con los jobs `manager` (`manager:9291`), `notificator` (`notificator:9391`) y `gateway` (`gateway:9191`), `metrics_path: /actuator/prometheus` y label `app`; verificar con `docker run --rm -v $PWD/observability/prometheus:/etc/prometheus:ro --entrypoint promtool prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml`
- [x] 1.2 Servicio `prometheus` en `docker-compose.yml` (`127.0.0.1:9090:9090`, config `:ro`, volumen `prometheus-data`); verificar con `docker compose up -d prometheus manager notificator` que `up{app="manager"}` y `up{app="notificator"}` valen 1 y `up{app="gateway"}` vale 0
- [x] 1.3 Histograma de `notificator.webhook.latency` por property en `notificator/src/main/resources/application.yml`; verificar con `mvn test` en `notificator/` y que `/actuator/prometheus` del contenedor expone `notificator_webhook_latency_seconds_bucket` tras una entrega

## 2. Logs

- [x] 2.1 Crear `observability/loki/loki.yml` (single binary, `auth_enabled: false`, filesystem, schema `v13`/`tsdb`) y el servicio `loki` sin puertos publicados, con volumen `loki-data`; verificar con `docker compose exec loki wget -qO- localhost:3100/ready` → `ready`
- [x] 2.2 Crear `observability/alloy/config.alloy` (discovery docker → relabel a `app` filtrando `manager|notificator|gateway` → `stage.json` de `log.level` → label `level` → Loki) y el servicio `alloy` con `/var/run/docker.sock:ro`, sin puertos publicados; verificar con `logcli`/API de Loki que `{app="notificator"}` devuelve líneas y que `/loki/api/v1/labels` no incluye `client_id` ni `event_id`

## 3. Grafana

- [x] 3.1 Provisioning de datasources (`uid` fijos `prometheus` y `loki`) y provider de dashboards (`allowUiUpdates: false`) en `observability/grafana/provisioning/`; servicio `grafana` en `127.0.0.1:3000`, volumen `grafana-data`, admin desde `GRAFANA_ADMIN_PASSWORD`, anónimo deshabilitado; verificar con `curl -u admin:$GRAFANA_ADMIN_PASSWORD localhost:3000/api/datasources` que lista ambos y que `/api/datasources/uid/prometheus/health` y `/uid/loki/health` responden OK
- [x] 3.2 Dashboard `observability/grafana/dashboards/apps.json` con las variables y paneles del design (targets, entregas por outcome y por cliente, DLT, descartes, recovery, p95 webhook, replays, HTTP por status, logs); verificar con el volumen de Grafana vacío (`docker compose down -v grafana`) que el dashboard aparece y con `--profile simulator` los paneles de entregas y logs muestran datos
- [x] 3.3 Agregar `GRAFANA_ADMIN_PASSWORD=change-me-grafana` a `.env.example`; verificar con `git grep -n GRAFANA_ADMIN_PASSWORD` que solo aparece en `.env.example`, `docker-compose.yml` y docs

## 4. Logs estructurados

- [x] 4.1 notificator: pasar los logs de `DeliveryService`, `ExhaustedEventService`, `StaleDeliveryRecoveryService` y `TransactionEventListener` a `log.atX().addKeyValue(...)` con mensaje fijo; extender el `tearDown` de `DeliveryServiceTest` para que tampoco haya `content` en los key-values y agregar una aserción de que el log de `DELIVERED` lleva `event_id` y `client_id` como key-values; verificar con `mvn test` en `notificator/`
- [x] 4.2 manager: lo mismo en `NotificationEventReplayService` y `NotificationEventQueryService`, con un test que verifique los key-values del log `Replay requested`; verificar con `mvn test` en `manager/`
- [x] 4.3 Con el stack levantado, `{app="notificator"} | json | client_id="CLIENT001"` en Loki devuelve la línea de una entrega `DELIVERED` de `CLIENT001`, y `/loki/api/v1/labels` sigue sin `client_id` ni `event_id`

## 5. Documentación

- [x] 5.1 README: sección de observabilidad con URLs (Grafana `localhost:3000`, Prometheus `localhost:9090`), credenciales desde `.env`, consulta LogQL de ejemplo por `client_id`, puerto 9191 reservado para el actuator del gateway, y la nota de que el socket de Docker en Alloy es solo para local; verificar que los comandos y URLs documentados funcionan tal cual

## 6. Verificación integrada

- [x] 6.1 Desde cero (`docker compose down -v && docker compose up -d --build`, más `--profile simulator`): `docker compose ps` muestra solo 3000, 9090, 9290, 3306 y 9094 publicados; en Grafana el dashboard muestra entregas, latencia p95 y logs de `notificator` en menos de 1 minuto, y un `POST /notification_events/{id}/replay` al manager se refleja en el panel de replays

## 7. Lectura legible de logs

- [x] 7.1 Panel de logs del dashboard: `| json | line_format` con nivel, logger simple, mensaje, `event_id`, `client_id`, `error` y `exception=` con el stack trace escapado; verificar contra la API de Loki que una entrega se ve como `INFO DeliveryService Event delivered event_id=... client_id=...` y que un mensaje inválido enviado a `transactions.events.v1` produce una línea `exception=...` sin saltos de línea
- [x] 7.2 `observability/logs.jq` para `docker compose logs --no-log-prefix`: mismo formato, todos los campos de primer nivel no ECS como `clave=valor`, `error` string como campo y objeto como `exception=`, líneas no JSON sin cambios; verificar que la cantidad de líneas formateadas es igual a la de líneas JSON con un error en el DLT incluido, y con una línea sintética con `error` string y otra con `error` objeto
- [x] 7.3 README: en la sección de observabilidad, cómo leer los logs en la terminal con `logs.jq` (requiere `jq`, `--no-log-prefix`) y que el panel de Grafana los muestra formateados; verificar que el comando documentado funciona tal cual
