# Proposal

## Why

El manager y el notificator ya exponen métricas Micrometer en `/actuator/prometheus` y escriben logs JSON a stdout, pero nadie los recolecta: para ver una entrega fallida o la latencia del webhook hay que hacer `curl` al actuator o leer `docker logs`. El caso pide observabilidad casi en tiempo real, así que el `docker compose up` local tiene que levantar Prometheus, Loki y Grafana listos para usar.

## What Changes

- `docker-compose.yml` agrega los servicios `prometheus`, `loki`, `alloy` y `grafana`.
  - Alloy no fue pedido explícitamente, pero sin él Loki queda vacío: es el colector de logs ya definido en el stack del proyecto.
- Prometheus scrapea el actuator de `manager` (9291), `notificator` (9391) y `gateway` (9191, asumido; ver design). El gateway todavía no existe: su target aparece `DOWN` hasta que se implemente, sin tocar la configuración.
- Alloy lee los logs de los contenedores de las apps y los envía a Loki con labels `app` y `level`; `client_id` y `event_id` quedan como campos del JSON, no como labels.
- Lectura legible de los logs sin cambiar su formato: las apps siguen escribiendo JSON ECS a stdout (convención del proyecto: el formato resuelve el escape de comillas, saltos de línea y stack traces). El panel de logs de Grafana los muestra con `line_format` como una línea por evento (`INFO DeliveryService Event delivered event_id=... client_id=...`), y `observability/logs.jq` hace lo mismo para `docker compose logs`. En ambos casos una excepción queda en la misma línea, en el campo `exception` con los saltos de línea escapados como `\n`.
- `notificator` y `manager` pasan `event_id`, `client_id` y el resto de los datos de cada log (`attempt`, `reason`, `http_status`, ...) como key-values SLF4J en vez de interpolarlos en el texto del mensaje, para que salgan como campos del JSON y se filtren en Loki con `| json | client_id="..."`. Los mensajes quedan como texto fijo (`Event delivered`).
- `.env.example` agrega la contraseña del admin de Grafana; README documenta URLs y puertos.

Aplicaciones tocadas: **notificator** (property de métricas y llamadas de log) y **manager** (llamadas de log). El formato de los logs no cambia. **gateway** no cambia; solo se configura como target.

### Fuera de alcance

- Métricas nuevas de negocio en el manager o el notificator (las existentes alcanzan).
- Logs nuevos o cambios de nivel: solo cambia la forma de los existentes.
- Instrumentar el gateway: se hace cuando se implemente; este cambio solo deja su target configurado.
- Alertas (Alertmanager / Grafana alerting).
- Tracing distribuido (Tempo, OpenTelemetry).
- Métricas de Kafka y MariaDB (exporters).
- Persistencia de largo plazo, retención configurada o HA del stack; es solo para local.

## Capabilities

### New Capabilities

- `observability-stack`: recolección de métricas y logs de las apps y su visualización en Grafana en el entorno local de docker compose.

### Modified Capabilities

(ninguna)

## Impact

- `docker-compose.yml`: 4 servicios nuevos y volúmenes para Prometheus, Loki y Grafana.
- Archivos nuevos de configuración en `observability/` (Prometheus, Loki, Alloy, provisioning y dashboard de Grafana) y el filtro `observability/logs.jq` para leer logs en la terminal (requiere `jq` en el host).
- `notificator/src/main/resources/application.yml`: histograma de latencia del webhook.
- Llamadas de log en `DeliveryService`, `ExhaustedEventService`, `StaleDeliveryRecoveryService`, `TransactionEventListener` (notificator) y `NotificationEventReplayService`, `NotificationEventQueryService` (manager).
- `.env.example`, `README.md`.
- Imágenes nuevas: `prom/prometheus`, `grafana/loki`, `grafana/alloy`, `grafana/grafana` (todas dentro del stack declarado).
