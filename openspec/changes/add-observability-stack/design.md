# Design

## Context

- `manager` expone el actuator en el puerto 9291 (separado del 9290 de la API) y `notificator` en 9391; ambos con `health,prometheus` y Micrometer + Prometheus ya en el `pom.xml`. Ninguno de esos puertos se publica en compose.
- Métricas existentes: `notificator.deliveries`, `notificator.discarded`, `notificator.dlt.messages`, `notificator.webhook.latency`, `notificator.recovery.events` (ver README) y `manager.replay.requests`. `notificator.webhook.latency` es un `Timer` sin histograma: no se puede calcular p95 en Prometheus.
- Logs: `logging.structured.format.console: ecs` en ambas apps (nivel en `log.level`, app en `service.name`). Los ids van interpolados en `message` (`"Event delivered event_id=EVT1 client_id=CLIENT001 ..."`), no como campos del JSON.
- `gateway/` está vacío: no hay servicio en compose.

## Goals / Non-Goals

**Goals:**
- Todo el stack se configura con archivos en `observability/`, versionados y montados read-only.
- Agregar el gateway después no requiere tocar Prometheus, Alloy ni Grafana.

**Non-Goals:**
- Configuración apta para producción (auth de Prometheus/Loki, retención, HA).

## Decisions

### Layout de archivos

```
observability/
  prometheus/prometheus.yml
  loki/loki.yml
  alloy/config.alloy
  grafana/provisioning/datasources/datasources.yml
  grafana/provisioning/dashboards/dashboards.yml
  grafana/dashboards/apps.json
```

Una carpeta por herramienta, todo montado `:ro`. Alternativa descartada: meterlo en `docs/` o por app; la config es de infraestructura compartida.

### Imágenes (versiones fijas)

`prom/prometheus:v3.5.0`, `grafana/loki:3.5.0`, `grafana/alloy:v1.10.0`, `grafana/grafana:12.1.0`. Todas están en el stack declarado. Versiones fijas como el resto del compose (`mariadb:12.3.3`, `apache/kafka:4.1.0`); se verifica que el tag exista al implementar.

### Prometheus: `static_configs`, un job por app

```yaml
global: { scrape_interval: 15s }
scrape_configs:
  - job_name: manager
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ["manager:9291"], labels: { app: manager } }]
  - job_name: notificator   # notificator:9391
  - job_name: gateway       # gateway:9191
```

- Alternativa descartada: `docker_sd_configs` (requiere el socket de Docker en Prometheus y labels en cada servicio). Tres targets fijos no lo justifican.
- **Puerto del gateway: 9191** (asumido). Sigue el patrón de puertos (manager 9290/9291, notificator 9391); cuando se implemente el gateway, su actuator va en 9191. Hasta entonces el target queda `DOWN`, que es la señal correcta.
- `scrape_interval: 15s`: suficiente para "casi tiempo real" en local.
- Volumen `prometheus-data` para no perder series al recrear el contenedor.

### Logs: Alloy lee los contenedores por el socket de Docker

`discovery.docker` → `discovery.relabel` (se queda con servicios `manager|notificator|gateway` vía el label `com.docker.compose.service`, que pasa a `app`) → `loki.source.docker` → `loki.process` (`stage.json` extrae `log.level`, `stage.labels` lo publica como `level`) → `loki.write` a `http://loki:3100`.

- Se descartan los logs de infra (Kafka, MariaDB, el propio stack) para no mezclarlos con los de negocio; se agregan cambiando la regex.
- `client_id`/`event_id` no se extraen como labels: cardinalidad sin límite. Se filtran en consulta con `| json`.
- Alternativa descartada: driver de logging `loki` de Docker. Requiere instalar un plugin en el host y cambia `docker logs`.
- Alternativa descartada: Promtail. Está deprecado a favor de Alloy, que ya es parte del stack.

### Loki: single binary, filesystem

Config mínima `auth_enabled: false`, almacenamiento `filesystem` en el volumen `loki-data`, schema `v13` con `tsdb`. Sin retención configurada (local).

`limits_config.discover_service_name: []`: Loki 3.5 agrega por defecto el label `service_name`, que choca con el campo `service.name` del JSON ECS al aplicar `| json` (lo renombra a `service_name_extracted`) y en esa colisión devuelve la línea alterada. Sin ese label solo quedan `app` y `level`.

### Grafana: provisioning

- Datasources con `uid` fijo (`prometheus`, `loki`) para que el JSON del dashboard los referencie de forma estable.
- Provider de dashboards apuntando a `/var/lib/grafana/dashboards` (montaje de `observability/grafana/dashboards`), `allowUiUpdates: false`: el repo es la fuente de verdad.
- Admin: `GF_SECURITY_ADMIN_USER=admin`, `GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}`. `GF_AUTH_ANONYMOUS_ENABLED=false` (default, explícito).
- Volumen `grafana-data`.

### Dashboard `apps.json` (un solo dashboard)

Variables: `client_id` (multi, desde `label_values(notificator_deliveries_total, client_id)`) y `level` (multi, desde Loki `label_values(level)`).

| Panel | Query |
|---|---|
| Targets arriba | `up{app=~"manager\|notificator\|gateway"}` |
| Entregas por outcome | `sum by (outcome) (rate(notificator_deliveries_total{client_id=~"$client_id"}[5m]))` |
| Entregas por cliente | `sum by (client_id) (rate(notificator_deliveries_total{client_id=~"$client_id"}[5m]))` |
| Mensajes al DLT | `sum by (reason) (increase(notificator_dlt_messages_total[5m]))` |
| Descartes | `sum by (reason) (rate(notificator_discarded_total[5m]))` |
| Recovery | `sum by (action) (increase(notificator_recovery_events_total[5m]))` |
| Latencia webhook p95 | `histogram_quantile(0.95, sum by (le, outcome) (rate(notificator_webhook_latency_seconds_bucket[5m])))` |
| Replays del manager | `sum by (outcome) (rate(manager_replay_requests_total[5m]))` |
| HTTP por status | `sum by (app, status) (rate(http_server_requests_seconds_count[5m]))` |
| Logs | Loki: `{app=~"manager\|notificator\|gateway", level=~"$level"}` |

Un solo dashboard: tres apps y ~10 paneles no justifican varios. Los paneles del gateway aparecen vacíos hasta que exista.

### Ids como campos del JSON: key-values de SLF4J

Cada log pasa a la API fluida de SLF4J 2:

```java
log.atInfo()
    .addKeyValue("event_id", event.eventId())
    .addKeyValue("client_id", event.clientId())
    .addKeyValue("attempt", attempt)
    .log("Event delivered");
```

El formatter ECS de Spring Boot escribe los key-values como campos de primer nivel (`"client_id":"CLIENT001"`), junto al MDC. Todos los datos que hoy van como `clave={}` en el mensaje pasan a key-value con la misma clave; el mensaje queda como texto fijo. Las excepciones se pasan con `setCause(e)`.

- Alternativa descartada: MDC seteado en el listener de Kafka. No cubre el job de recuperación (itera filas) ni el manager, y obliga a limpiar el MDC en cada salida; quedarían dos mecanismos.
- Alternativa descartada: `StructuredArguments` de logstash-logback-encoder. Dependencia nueva para algo que SLF4J 2 ya trae.
- Sin dependencias nuevas: SLF4J 2 viene con Spring Boot.
- En el manager `client_id` ya está en el MDC (lo pone `ClientIdentificationFilter` en cada request), así que no se repite como key-value: el formatter ECS rechaza claves duplicadas entre MDC y key-values y descarta la línea entera (`Duplicate nested pairs added under 'client_id'`).

### Lectura de logs: JSON en stdout, formato legible al leer

Las apps siguen escribiendo JSON ECS. El formato legible se arma al leer, en dos lugares:

```
apps --JSON--> stdout --+--> docker compose logs --no-log-prefix | jq -Rr -f observability/logs.jq
                        |
                        +--> Alloy --> Loki --> Grafana: | json | line_format `...`
```

Una línea por evento: nivel, logger (nombre simple de la clase), mensaje, campos del evento y, si hay excepción, `exception=` con el stack trace en la misma línea (`\n` y `\t` escapados).

```
INFO DeliveryService Event delivered event_id=EVT-222d client_id=CLIENT001
ERROR TransactionEventListener Invalid message sent to DLT error=Invalid transaction event: malformed JSON (StreamReadException)
ERROR DeadLetterPublishingRecovererFactory Record: ... exception=org.springframework.kafka.listener.ListenerExecutionFailedException: Listener failed\n\tat ...
```

- **Grafana**: el `line_format` del panel de logs muestra `event_id`, `client_id`, `error` y el stack trace. El resto de los campos (`attempt`, `http_status`, ...) siguen en el detalle de la línea, porque un template de LogQL no puede recorrer campos arbitrarios. La fecha y el label `app` los pone el panel.
- **Terminal**: `observability/logs.jq` imprime todos los campos de primer nivel que no son de ECS como `clave=valor` y deja pasar sin cambios las líneas que no son JSON. Requiere `jq` y `--no-log-prefix`, porque el prefijo `servicio |` de compose rompe el JSON.
- `error` tiene dos significados: el key-value `error` de las apps es un string (motivo del fallo), mientras que ECS pone la excepción en el objeto `error` (`type`, `message`, `stack_trace`). Los dos formateadores distinguen por tipo y escriben la excepción como `exception=`.
- Alternativa descartada: cambiar el formato de stdout a logfmt. Con un pattern de logback, `%kvp` no escapa las comillas de los valores (`error` trae mensajes de RestClient con comillas y de Jackson con saltos de línea), así que las líneas se parten o se parsean mal. Con un `StructuredLogFormatter` propio se resuelve, pero son ~40 líneas duplicadas en dos apps para reimplementar un escape que JSON ya trae, y rompe la convención del proyecto.
- Alternativa descartada: script `logs.sh` que envuelva `docker compose logs`. Un archivo `.jq` y una línea en el README alcanzan.

### Histograma de latencia del webhook

En `notificator/src/main/resources/application.yml`:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        notificator.webhook.latency: true
```

Property en vez de `Timer.builder(...).publishPercentileHistogram()`: no toca código ni tests. Alternativa descartada: `percentiles: 0.95` en el cliente, porque no se puede agregar entre instancias.

## Risks / Trade-offs

- [Alloy monta `/var/run/docker.sock`; acceso al socket equivale a root en el host — OWASP A05 Security Misconfiguration] → montaje `:ro`, sin puertos publicados, solo para local. En producción los logs se recolectan con el agente de la plataforma (DaemonSet, driver de la nube); se documenta en el README.
- [Grafana con credenciales débiles expuesto en la red — OWASP A07 Identification and Authentication Failures] → puerto en `127.0.0.1`, contraseña desde `.env`, anónimo deshabilitado.
- [`client_id` como label de Prometheus en `notificator_deliveries_total` crece con la cantidad de clientes] → ya existe y es requisito del caso (entregas por cliente); aceptable mientras los clientes sean miles, no millones. Loki no lo usa como label.
- [Datos financieros en logs — OWASP A02/A09] → las apps no loguean `content` y este cambio no agrega datos nuevos a los logs, solo los mueve a campos. El test del `DeliveryService` que verifica que `content` nunca aparece se extiende a los key-values.
- [Puerto del gateway asumido (9191)] → si el gateway usa otro, se cambia una línea en `prometheus.yml`.
- [El formato legible es solo para leer: un valor con texto libre (`error`, `message`) puede contener `client_id=OTRO` y confundir a quien lee la línea — OWASP A09 Security Logging and Monitoring Failures] → los filtros se hacen siempre sobre el JSON (`| json | client_id=...`), nunca sobre la línea formateada; el JSON almacenado no cambia.
- [Tags de imagen inexistentes] → se verifica con `docker compose pull` en la primera tarea.

## Migration Plan

Aditivo. `docker compose up -d` crea los servicios nuevos; el notificator se reconstruye por la property nueva. Rollback: quitar los servicios del compose y `docker compose down --remove-orphans`.
