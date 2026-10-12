# Proposal

## Why

`manager` y `notificator` ya exportan las métricas de la JVM (memoria, GC, threads, clases, CPU, Hikari, executors) y Prometheus las scrapea. Pero ningún dashboard ni alerta las usa, así que una fuga de memoria, pausas largas de GC o un pool de conexiones agotado no se ven en Grafana. Además, el heap máximo hoy sale del default ergonómico (25% de la RAM del host), así que cambia según la máquina donde corre el stack.

## What Changes

- Nuevo dashboard **JVM** en Grafana, provisionado desde el repo como los demás (tag `cobre`, no editable), con una variable `app` (`manager` | `notificator`). Muestra:
  - un resumen: uptime, versión de la JVM, CPU del proceso y heap después del GC;
  - memoria heap y non-heap por pool, y buffers;
  - GC: pausas por causa, overhead y tasas de asignación y promoción;
  - threads por estado, clases cargadas y archivos abiertos;
  - el pool de conexiones a la base (Hikari) y los executors de Spring.
- `manager` y `notificator` arrancan con el heap fijo en 2 GB (mínimo = máximo).
- El README suma el dashboard **JVM** a la lista de dashboards.

Aplicaciones que toca: `manager` y `notificator`, solo por el flag de heap en su imagen. Ninguna cambia código ni dependencias. `gateway` no existe todavía y no se toca.

**Fuera de alcance:**
- Alertas sobre métricas de la JVM.
- Instrumentar `transaction-simulator` y `webhook-mock`.
- Límites de memoria del contenedor (`mem_limit`).
- Métricas de virtual threads: ni `manager` ni `notificator` los usan.
- Cambiar el recolector de basura o cualquier otro flag de la JVM.

## Capabilities

### New Capabilities

_Ninguna._

### Modified Capabilities

- `operations-dashboards`: se agrega el requisito del dashboard **JVM**. La capability la introduce el change `add-grafana-dashboards`, que todavía no está archivado.

## Impact

- `observability/grafana/dashboards/jvm.json` (nuevo).
- `manager/Dockerfile` y `notificator/Dockerfile`: los flags de heap en el `ENTRYPOINT`.
- `README.md`: la sección de dashboards.
- Recursos: entre las dos apps reservan 4 GB de heap desde el arranque, más unos 300 a 500 MB fuera del heap cada una.
- No cambian Prometheus, Alloy, las alertas ni las dependencias Maven.
