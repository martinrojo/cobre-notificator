# Design

## Context

- **Métricas que ya existen.** Con `spring-boot-starter-actuator` y `micrometer-registry-prometheus`, las dos apps ya publican los binders de JVM, proceso, Hikari y executors en `/actuator/prometheus`, y Prometheus las scrapea con el label `app` (`manager:9291`, `notificator:9391`). Verificado contra el stack levantado:
  - **Memoria:** `jvm_memory_used/committed/max_bytes{area,id}`, con los pools de G1 (`G1 Eden Space`, `G1 Old Gen`, `G1 Survivor Space`) y non-heap (`Metaspace`, `Compressed Class Space`, `CodeHeap '...'`). También `jvm_memory_usage_after_gc{area,pool}` y `jvm_buffer_*{id}`.
  - **GC:** `jvm_gc_pause_seconds_{count,sum,max}{gc,action,cause}`, `jvm_gc_overhead`, `jvm_gc_memory_allocated_bytes_total`, `jvm_gc_memory_promoted_bytes_total`, `jvm_gc_live_data_size_bytes` y `jvm_gc_max_data_size_bytes`. En el notificator ya aparece `cause="G1 Humongous Allocation"`.
  - **Threads y clases:** `jvm_threads_{live,daemon,peak}_threads`, `jvm_threads_states_threads{state}`, `jvm_classes_loaded_classes`, `jvm_classes_unloaded_classes_total` y `jvm_compilation_time_ms_total`.
  - **Proceso:** `process_cpu_usage`, `system_cpu_usage`, `system_cpu_count`, `process_uptime_seconds`, `process_files_{open,max}_files` y `jvm_info{version,vendor,runtime}`.
  - **Recursos:** `hikaricp_connections_{active,idle,pending,max}`, `hikaricp_connections_timeout_total`, `hikaricp_connections_acquire_seconds_*` y `executor_{active_threads,queued_tasks,pool_size_threads,pool_max_threads}{name}`. Los executors son `applicationTaskExecutor` en las dos apps y `taskScheduler` solo en el notificator.
- **Heap actual.** Ninguna app pasa flags a la JVM. El máximo queda en ~1.8 GiB (25% de la RAM del host) y el committed en 70 a 130 MB. Las imágenes arrancan con `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`.
- **Logs.** Alloy manda a Loki la salida de los contenedores y parsea el nivel desde el JSON (ECS). Hoy todas las líneas de las dos apps son JSON.
- **Convenciones de los dashboards** (ver el design de `add-grafana-dashboards`, decisión 8): un archivo por dashboard en `observability/grafana/dashboards/` con uid igual al nombre del archivo, tag `cobre`, `editable: false`, `refresh: 30s`, `now-1h`, `schemaVersion: 41` y datasource por uid (`prometheus`).

## Goals / Non-Goals

**Goals:**
- Cada panel sale de una métrica que ya se scrapea: no hay código Java nuevo, ni dependencias, ni cambios en Prometheus.
- El dashboard sirve para las dos apps con la misma definición, por medio de `$app`.

**Non-Goals:**
- Comparar `manager` y `notificator` en el mismo panel. La variable es de selección única; para comparar se abren dos pestañas.
- Ver la memoria del contenedor (RSS, cgroup). No hay cAdvisor, y el heap fijo hace que el número relevante sea el de la JVM.

## Decisions

### 1. Un dashboard `jvm.json`, no paneles en los existentes

`General` responde "¿se entregan las notificaciones?" y la JVM es otra pregunta. Un dashboard aparte con `$app` mantiene `General` corto y sirve para las dos apps.

- **Alternativa descartada:** una fila colapsada "JVM" dentro de `General`. Habría que duplicar los paneles por app o meter una variable `app` que solo usa esa fila.
- **Alternativa descartada:** importar el dashboard de la comunidad "JVM (Micrometer)" (id 4701). Depende de labels `application` e `instance` que acá no existen, trae paneles de Tomcat y Log4j que no aplican y no sigue las convenciones del repo.

### 2. Variable `app`

`label_values(jvm_info, app)`, de selección única, sin "All", con `manager` por defecto. Todas las consultas filtran por `{app="$app"}`. Se lee de `jvm_info` porque solo la publican las apps Java: con `up` aparecería `kafka`, el job de Alloy.

### 3. Paneles y consultas

| Fila | Panel | Tipo | Consulta |
|---|---|---|---|
| Resumen | Uptime | stat | `process_uptime_seconds{app="$app"}` (unidad `s`) |
| Resumen | Versión | stat | `jvm_info{app="$app"}`, muestra el label `version` |
| Resumen | CPU del proceso | stat | `process_cpu_usage{app="$app"}` (unidad `percentunit`) |
| Resumen | Heap después del GC | stat/gauge | `jvm_memory_usage_after_gc{app="$app", area="heap", pool="long-lived"}` (`percentunit`) |
| Memoria | Heap usado vs máximo | timeseries apilado | `sum by (id)(jvm_memory_used_bytes{app="$app", area="heap"})` apilado, más la línea `sum(jvm_memory_max_bytes{app="$app", area="heap"} > 0)` sin apilar |
| Memoria | Non-heap por pool | timeseries | `sum by (id)(jvm_memory_used_bytes{app="$app", area="nonheap"})` |
| Memoria | Buffers | timeseries | `sum by (id)(jvm_buffer_memory_used_bytes{app="$app"})` |
| GC | Pausas por segundo por causa | timeseries | `sum by (cause)(rate(jvm_gc_pause_seconds_count{app="$app"}[2m]))` |
| GC | Pausa máxima por causa | timeseries | `max by (cause)(jvm_gc_pause_seconds_max{app="$app"})` |
| GC | Overhead | timeseries | `jvm_gc_overhead{app="$app"}` (`percentunit`) |
| GC | Asignación y promoción | timeseries | `rate(jvm_gc_memory_allocated_bytes_total{app="$app"}[2m])` y `rate(jvm_gc_memory_promoted_bytes_total{app="$app"}[2m])` (`Bps`) |
| Threads | Threads por estado | timeseries apilado | `sum by (state)(jvm_threads_states_threads{app="$app"})` |
| Threads | Clases cargadas | timeseries | `jvm_classes_loaded_classes{app="$app"}` |
| Threads | Archivos abiertos | timeseries | `process_files_open_files{app="$app"}` y `process_files_max_files{app="$app"}` |
| Recursos | Pool de la base | timeseries | `hikaricp_connections_active`, `_idle`, `_pending` y `_max`, todas con `{app="$app"}` |
| Recursos | Timeouts del pool | timeseries | `increase(hikaricp_connections_timeout_total{app="$app"}[5m])` |
| Recursos | Executors | timeseries | `sum by (name)(executor_active_threads{app="$app"})` y `sum by (name)(executor_queued_tasks{app="$app"})` |

- **Heap después del GC como número de salud.** Con `-Xms` igual a `-Xmx`, el heap usado sube y baja con cada ciclo, así que su valor puntual no dice nada. Lo que muestra presión real o una fuga es cuánto queda vivo después de recolectar. La serie trae `area="heap", pool="long-lived"` (Old Gen en G1), verificado en las dos apps.
- **Máximo del heap con `> 0`.** G1 reporta `-1` como máximo de Eden y Survivor (no tienen tope propio): sin el filtro, la suma da 2 bytes menos que 2 GiB.
- **Sin línea de "committed".** Con el heap fijo vale siempre 2 GiB y repetiría la línea del máximo.
- **Pausa máxima.** `jvm_gc_pause_seconds_max` es el máximo de una ventana deslizante de Micrometer (unos 2 minutos), no del rango del dashboard. Se lee como "peor pausa reciente".

### 4. Heap fijo con flags en el `ENTRYPOINT` de cada Dockerfile

`ENTRYPOINT ["java", "-Xms2g", "-Xmx2g", "-jar", "/app/app.jar"]` en `manager/Dockerfile` y `notificator/Dockerfile`.

- **Alternativa descartada:** `JAVA_TOOL_OPTIONS` en `docker-compose.yml`. Es lo que se discutió primero, pero la JVM escribe `Picked up JAVA_TOOL_OPTIONS: ...` en stderr al arrancar. Esa línea no es JSON: llega a Loki sin `level` y ensucia el dashboard `Logs` y `observability/logs.jq`. `JDK_JAVA_OPTIONS` tiene el mismo problema con su aviso `NOTE: Picked up ...`.
- **Alternativa descartada:** `-XX:MaxRAMPercentage` con `mem_limit` en compose. El heap quedaría atado al límite del contenedor, y lo pedido es un valor fijo.
- **Costo:** cambiar el heap obliga a reconstruir la imagen. Se acepta: es un valor que no cambia seguido, y queda junto al comando que arranca la app.
- **Mínimo igual al máximo:** la JVM reserva y compromete el heap al arrancar, y nunca lo agranda ni lo achica. No hay pausas por redimensionar el heap y el panel de memoria tiene un techo fijo. No se agrega `-XX:+AlwaysPreTouch`: tarda el arranque y no aporta en local.

## Risks / Trade-offs

- **[4 GB comprometidos en la máquina local]** Hoy las apps usan 70 a 130 MB de heap. Con `-Xms2g` el SO no entrega las páginas hasta que se tocan, pero el heap puede crecer hasta 2 GiB sin GC de Old Gen. En una máquina con poca RAM el stack completo puede no entrar → se documenta en el README. Si molesta, se baja el valor en los Dockerfiles.
- **[Sin `mem_limit`]** Si la memoria fuera del heap crece (Metaspace, direct buffers de Kafka), nada la corta → se ve en los paneles de non-heap y de buffers. No se agrega límite porque está fuera de alcance.
- **[Seguridad]** No se exponen endpoints nuevos: `/actuator/prometheus` ya existe, en un puerto que no se publica en el host. El dashboard muestra la versión exacta de la JVM, un dato útil para un atacante (OWASP A05 Security Misconfiguration), pero Grafana solo escucha en `127.0.0.1` y requiere login, igual que el resto de los dashboards.

## Migration Plan

1. Archivar `add-grafana-dashboards` antes que este change: el delta de `operations-dashboards` agrega requisitos a la capability que crea ese change.
2. `docker compose up -d --build manager notificator` y `docker compose restart grafana`. Grafana carga `jvm.json` desde el directorio de provisioning.
3. Rollback: revertir el commit y repetir el paso 2. Las apps vuelven al heap ergonómico.
